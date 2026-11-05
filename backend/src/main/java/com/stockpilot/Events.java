package com.stockpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration class Topics {
    @Bean NewTopic inventoryChanged(){return TopicBuilder.name("inventory.changed").partitions(3).replicas(1).build();}
    @Bean NewTopic lowStock(){return TopicBuilder.name("inventory.low-stock").partitions(3).replicas(1).build();}
    @Bean NewTopic purchaseReceived(){return TopicBuilder.name("purchase.received").partitions(3).replicas(1).build();}
    @Bean NewTopic salesFulfilled(){return TopicBuilder.name("sales.fulfilled").partitions(3).replicas(1).build();}
    @Bean NewTopic failedStock(){return TopicBuilder.name("inventory.update-failed").partitions(3).replicas(1).build();}
    @Bean NewTopic overdueDelivery(){return TopicBuilder.name("purchase.delivery-overdue").partitions(3).replicas(1).build();}
}

@Component class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate db;private final KafkaTemplate<String,String> kafka;private final ObjectMapper json;
    OutboxPublisher(JdbcTemplate db,KafkaTemplate<String,String> kafka,ObjectMapper json){this.db=db;this.kafka=kafka;this.json=json;}
    @Scheduled(fixedDelay=2000) @Transactional public void publish(){
        List<Map<String,Object>> rows=db.queryForList("SELECT id,event_type,aggregate_id,payload FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED");
        for(var row:rows){
            long id=((Number)row.get("id")).longValue();
            try{
                String value=json.writeValueAsString(Map.of("eventVersion",1,"eventId",id,"eventType",row.get("event_type"),"aggregateId",row.get("aggregate_id"),"payload",json.readTree(row.get("payload").toString())));
                kafka.send((String)row.get("event_type"),row.get("aggregate_id").toString(),value).get(5,TimeUnit.SECONDS);
                db.update("UPDATE outbox_events SET published_at=now() WHERE id=?",id);
            }catch(Exception ex){log.warn("Outbox event {} remains pending",id,ex);break;}
        }
    }
}

@Component class EventConsumers {
    private final JdbcTemplate db;private final ObjectMapper json;private final JavaMailSender mail;
    @Value("${stockpilot.notifications-from}") String from;
    EventConsumers(JdbcTemplate db,ObjectMapper json,JavaMailSender mail){this.db=db;this.json=json;this.mail=mail;}
    @KafkaListener(topics={"inventory.low-stock","inventory.update-failed","purchase.delivery-overdue"},groupId="stockpilot-alerts") @Transactional public void lowStock(String message) throws Exception {
        JsonNode event=json.readTree(message);long id=event.path("eventId").asLong();
        if(db.update("INSERT INTO consumed_events(consumer_name,event_id) VALUES('owner-email',?) ON CONFLICT DO NOTHING",id)==0)return;
        String type=event.path("eventType").asText();
        long variant=event.path("payload").path("variantId").asLong();
        var emails=db.queryForList("SELECT email FROM app_users WHERE role='OWNER' AND status='ACTIVE'",String.class);
        for(String recipient:emails){SimpleMailMessage mailMessage=new SimpleMailMessage();mailMessage.setFrom(from);mailMessage.setTo(recipient);
            if(type.equals("inventory.low-stock")){mailMessage.setSubject("StockPilot low stock: variant "+variant);mailMessage.setText("Available stock is "+event.path("payload").path("available").asText()+". Review reorder suggestions in StockPilot.");}
            else if(type.equals("inventory.update-failed")){mailMessage.setSubject("StockPilot stock update failed");mailMessage.setText("Stock update #"+event.path("aggregateId").asText()+" failed: "+event.path("payload").path("reason").asText());}
            else {mailMessage.setSubject("StockPilot purchase delivery overdue");mailMessage.setText("Purchase order #"+event.path("aggregateId").asText()+" has an overdue delivery. Review pending deliveries in StockPilot.");}
            mail.send(mailMessage);}
    }
    @KafkaListener(topics={"inventory.changed","purchase.received","sales.fulfilled"},groupId="stockpilot-operations") @Transactional public void operation(String message) throws Exception {
        long id=json.readTree(message).path("eventId").asLong();
        db.update("INSERT INTO consumed_events(consumer_name,event_id) VALUES('operations',?) ON CONFLICT DO NOTHING",id);
    }
}

@Component class DeliveryAlerts {
    private final JdbcTemplate db;private final StockService stock;
    DeliveryAlerts(JdbcTemplate db,StockService stock){this.db=db;this.stock=stock;}
    @Scheduled(cron="0 0 8 * * *",zone="UTC") @Transactional public void overdue(){
        for(Long id:db.queryForList("SELECT id FROM purchase_orders WHERE status IN ('SUBMITTED','PARTIALLY_RECEIVED') AND expected_at<current_date",Long.class)){
            if(db.update("INSERT INTO alert_dispatches(alert_type,reference_id,alert_date) VALUES('OVERDUE_PURCHASE',?,current_date) ON CONFLICT DO NOTHING",id)==1)
                stock.event("purchase.delivery-overdue",id,Map.of("purchaseOrderId",id));
        }
    }
}

@Component class ReservationExpiry {
    private final JdbcTemplate db;private final StockService stock;
    ReservationExpiry(JdbcTemplate db,StockService stock){this.db=db;this.stock=stock;}
    @Scheduled(fixedDelay=60000) @Transactional public void expire(){
        var ids=db.queryForList("SELECT id FROM reservations WHERE status='ACTIVE' AND expires_at<now() ORDER BY id LIMIT 100",Long.class);
        for(Long id:ids){
            Long order=db.queryForObject("SELECT l.sales_order_id FROM reservations r JOIN sales_lines l ON l.id=r.sales_line_id WHERE r.id=?",Long.class,id);
            stock.release(id,"EXPIRED");
            db.update("UPDATE sales_orders SET status='STOCK_REQUIRED' WHERE id=? AND status IN ('RESERVED','PICKING','PACKED')",order);
        }
    }
}
