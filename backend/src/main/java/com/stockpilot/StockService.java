package com.stockpilot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {
    private final JdbcTemplate db; private final ObjectMapper json;private final AuditService audit;
    StockService(JdbcTemplate db,ObjectMapper json,AuditService audit){this.db=db;this.json=json;this.audit=audit;}
    static void positive(long n){if(n<=0)throw new ApiSupport.Invalid("Quantity must be positive");}
    static void nonnegative(long n){if(n<0)throw new ApiSupport.Invalid("Quantity cannot be negative");}
    void exists(long variant,long location){
        Integer count=db.queryForObject("SELECT count(*) FROM product_variants v CROSS JOIN warehouse_locations l WHERE v.id=? AND l.id=? AND v.active AND l.active",Integer.class,variant,location);
        if(count==null || count==0)throw new ApiSupport.Missing("Active variant or location not found");
    }
    void ensure(long variant,long location){db.update("INSERT INTO stock_balances(variant_id,location_id) VALUES(?,?) ON CONFLICT DO NOTHING",variant,location);}
    Map<String,Object> lock(long variant,long location){return db.queryForMap("SELECT on_hand,reserved FROM stock_balances WHERE variant_id=? AND location_id=? FOR UPDATE",variant,location);}
    long available(Map<String,Object> row){return ((Number)row.get("on_hand")).longValue()-((Number)row.get("reserved")).longValue();}
    void event(String type,long aggregateId,Map<String,Object> payload){
        try{db.update("INSERT INTO outbox_events(event_type,aggregate_id,payload) VALUES(?,?,?::jsonb)",type,aggregateId,json.writeValueAsString(payload));}
        catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
    boolean acquire(String key,String signature){
        String hash=SecurityConfig.hash(signature);
        if(db.update("INSERT INTO stock_commands(idempotency_key,request_signature) VALUES(?,?) ON CONFLICT DO NOTHING",key,hash)==1)return true;
        String prior=db.queryForObject("SELECT request_signature FROM stock_commands WHERE idempotency_key=?",String.class,key);
        if(!hash.equals(prior))throw new ApiSupport.Conflict("Idempotency key used for a different stock command");
        return false;
    }
    void checkLowStock(long variant){
        Long threshold=db.queryForObject("SELECT p.reorder_threshold FROM product_variants v JOIN products p ON p.id=v.product_id WHERE v.id=?",Long.class,variant);
        Long total=db.queryForObject("SELECT COALESCE(sum(on_hand-reserved),0) FROM stock_balances WHERE variant_id=?",Long.class,variant);
        if(threshold!=null && total!=null && total<=threshold && db.update("INSERT INTO alert_dispatches(alert_type,reference_id,alert_date) VALUES('LOW_STOCK',?,current_date) ON CONFLICT DO NOTHING",variant)==1)
            event("inventory.low-stock",variant,Map.of("variantId",variant,"available",total,"threshold",threshold));
    }
    @Transactional public void move(long variant,long location,long delta,String type,String refType,long refId,String key,Long actor){
        if(delta==0)throw new ApiSupport.Invalid("Movement cannot be zero");
        if(key==null||key.isBlank())throw new ApiSupport.Invalid("Idempotency key required");
        if(!acquire(key,variant+":"+location+":"+delta+":"+type+":"+refType+":"+refId))return;
        exists(variant,location);ensure(variant,location);
        Map<String,Object> balance=lock(variant,location);
        if(delta<0 && available(balance)<-delta)throw new ApiSupport.StockConflict("Insufficient available stock");
        db.update("UPDATE stock_balances SET on_hand=on_hand+?,updated_at=now() WHERE variant_id=? AND location_id=?",delta,variant,location);
        db.update("INSERT INTO stock_movements(variant_id,location_id,delta,movement_type,reference_type,reference_id,idempotency_key,actor_id) VALUES(?,?,?,?,?,?,?,?)",variant,location,delta,type,refType,refId,key,actor==null?audit.actorId():actor);
        audit.record(type,"STOCK_BALANCE",variant);
        event("inventory.changed",variant,Map.of("variantId",variant,"locationId",location,"delta",delta,"type",type));
        checkLowStock(variant);
    }
    @Transactional public void reserve(long lineId,long variant,long location,long qty,int minutes){
        positive(qty);exists(variant,location);ensure(variant,location);
        Map<String,Object> b=lock(variant,location);
        if(available(b)<qty)throw new ApiSupport.StockConflict("Insufficient available stock");
        db.update("UPDATE stock_balances SET reserved=reserved+?,updated_at=now() WHERE variant_id=? AND location_id=?",qty,variant,location);
        db.update("INSERT INTO reservations(sales_line_id,variant_id,location_id,quantity,expires_at) VALUES(?,?,?,?,now()+(? * interval '1 minute'))",lineId,variant,location,qty,minutes);
        audit.record("STOCK_RESERVED","SALES_LINE",lineId);
        event("inventory.changed",variant,Map.of("variantId",variant,"locationId",location,"reservedDelta",qty));
        checkLowStock(variant);
    }
    @Transactional public void release(long reservationId,String status){
        var r=db.queryForMap("SELECT variant_id,location_id,quantity,status FROM reservations WHERE id=? FOR UPDATE",reservationId);
        if(!"ACTIVE".equals(r.get("status")))return;
        long variant=((Number)r.get("variant_id")).longValue(),location=((Number)r.get("location_id")).longValue(),qty=((Number)r.get("quantity")).longValue();
        ensure(variant,location);lock(variant,location);
        db.update("UPDATE stock_balances SET reserved=reserved-?,updated_at=now() WHERE variant_id=? AND location_id=?",qty,variant,location);
        db.update("UPDATE reservations SET status=? WHERE id=?",status,reservationId);
        audit.record("RESERVATION_"+status,"RESERVATION",reservationId);
        event("inventory.changed",variant,Map.of("variantId",variant,"locationId",location,"reservedDelta",-qty));
    }
    @Transactional public void consume(long reservationId,Long actor){
        var r=db.queryForMap("SELECT variant_id,location_id,quantity,status,sales_line_id,expires_at FROM reservations WHERE id=? FOR UPDATE",reservationId);
        if(!"ACTIVE".equals(r.get("status")))throw new ApiSupport.Conflict("Reservation is not active");
        long variant=((Number)r.get("variant_id")).longValue(),location=((Number)r.get("location_id")).longValue(),qty=((Number)r.get("quantity")).longValue();
        ensure(variant,location);lock(variant,location);
        db.update("UPDATE stock_balances SET reserved=reserved-?,on_hand=on_hand-?,updated_at=now() WHERE variant_id=? AND location_id=?",qty,qty,variant,location);
        db.update("UPDATE reservations SET status='CONSUMED' WHERE id=?",reservationId);
        db.update("INSERT INTO stock_movements(variant_id,location_id,delta,movement_type,reference_type,reference_id,idempotency_key,actor_id) VALUES(?,? ,?,'SALE','SALES_LINE',? ,?,?)",variant,location,-qty,r.get("sales_line_id"),"sale-reservation-"+reservationId,actor==null?audit.actorId():actor);
        audit.record("SALE_SHIPPED","SALES_LINE",((Number)r.get("sales_line_id")).longValue());
        event("inventory.changed",variant,Map.of("variantId",variant,"locationId",location,"delta",-qty,"reservedDelta",-qty));
    }
    @Transactional public void transfer(long variant,long source,long target,long qty,String key,Long actor){
        positive(qty);if(source==target)throw new ApiSupport.Invalid("Locations must differ");
        if(!acquire("transfer:"+key,variant+":"+source+":"+target+":"+qty))return;
        exists(variant,source);exists(variant,target);ensure(variant,source);ensure(variant,target);
        lock(variant,Math.min(source,target));lock(variant,Math.max(source,target));
        move(variant,source,-qty,"TRANSFER_OUT","TRANSFER",target,key+":out",actor);
        move(variant,target,qty,"TRANSFER_IN","TRANSFER",source,key+":in",actor);
    }
    public List<Map<String,Object>> balances(){return db.queryForList("SELECT b.variant_id,v.sku,p.name AS product,l.id AS location_id,l.code AS location,w.name AS warehouse,b.on_hand,b.reserved,b.on_hand-b.reserved AS available FROM stock_balances b JOIN product_variants v ON v.id=b.variant_id JOIN products p ON p.id=v.product_id JOIN warehouse_locations l ON l.id=b.location_id JOIN warehouses w ON w.id=l.warehouse_id ORDER BY p.name,l.code");}
    public List<Map<String,Object>> movements(long variant){return db.queryForList("SELECT * FROM stock_movements WHERE variant_id=? ORDER BY id DESC LIMIT 500",variant);}
    public List<Map<String,Object>> reconciliation(){return db.queryForList("SELECT b.variant_id,b.location_id,b.on_hand,COALESCE(sum(m.delta),0) AS ledger_balance FROM stock_balances b LEFT JOIN stock_movements m ON m.variant_id=b.variant_id AND m.location_id=b.location_id GROUP BY b.variant_id,b.location_id,b.on_hand HAVING b.on_hand<>COALESCE(sum(m.delta),0)");}
}
