package com.stockpilot;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.Mockito.mock;
import org.springframework.security.test.context.support.WithMockUser;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties={"stockpilot.seed=false","spring.kafka.listener.auto-startup=false","spring.kafka.admin.auto-create=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockPilotIntegrationTest {
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("stockpilot").withUsername("test").withPassword("test");
    static {postgres.start();}
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
    @Autowired JdbcTemplate db;@Autowired StockService stock;@Autowired CatalogController catalog;@Autowired PurchasingController purchasing;@Autowired SalesController sales;@Autowired MockMvc mvc;@Autowired ProductRepository products;@Autowired ObjectMapper json;@Autowired AuthController auth;@Autowired PasswordEncoder encoder;@Autowired LoginThrottle throttle;
    long variant(){
        long product=db.queryForObject("INSERT INTO products(name) VALUES(?) RETURNING id",Long.class,"Product "+UUID.randomUUID());
        return db.queryForObject("INSERT INTO product_variants(product_id,sku,selling_price,purchase_cost) VALUES(?,?,10,5) RETURNING id",Long.class,product,"SKU-"+UUID.randomUUID());
    }
    long location(){long warehouse=db.queryForObject("INSERT INTO warehouses(name,code) VALUES(?,?) RETURNING id",Long.class,"Warehouse "+UUID.randomUUID(),"W-"+UUID.randomUUID());return db.queryForObject("INSERT INTO warehouse_locations(warehouse_id,code) VALUES(?,'A') RETURNING id",Long.class,warehouse);}
    long customer(){return db.queryForObject("INSERT INTO customers(name) VALUES('Test customer') RETURNING id",Long.class);}
    @Test @WithMockUser(roles="OWNER") void duplicateSkuAndAtomicMovements(){
        Product created=catalog.createProduct(new CatalogController.NewProduct("Created through JPA",null,"EACH",3));
        assertThat(products.findById(created.id)).isPresent();
        long product=db.queryForObject("INSERT INTO products(name) VALUES('Duplicate test') RETURNING id",Long.class);
        String sku="DUP-"+UUID.randomUUID();
        catalog.variant(new CatalogController.NewVariant(product,sku,null,"Standard",BigDecimal.TEN,BigDecimal.ONE));
        assertThatThrownBy(()->catalog.variant(new CatalogController.NewVariant(product,sku,null,"Standard",BigDecimal.TEN,BigDecimal.ONE))).isInstanceOf(Exception.class);
        long v=variant(),a=location(),b=location();
        stock.move(v,a,10,"ADJUSTMENT","TEST",1,"initial-"+v,null);
        stock.move(v,a,10,"ADJUSTMENT","TEST",1,"initial-"+v,null);
        stock.transfer(v,a,b,3,"transfer-"+v,null);
        stock.transfer(v,a,b,3,"transfer-"+v,null);
        assertThat(db.queryForObject("SELECT on_hand FROM stock_balances WHERE variant_id=? AND location_id=?",Long.class,v,a)).isEqualTo(7);
        assertThat(db.queryForObject("SELECT on_hand FROM stock_balances WHERE variant_id=? AND location_id=?",Long.class,v,b)).isEqualTo(3);
        assertThat(stock.reconciliation()).isEmpty();
    }
    @Test @WithMockUser(roles="OWNER") void purchaseReceiptAndCancellationRules(){
        long v=variant(),loc=location();long supplier=db.queryForObject("INSERT INTO suppliers(name) VALUES('Test supplier') RETURNING id",Long.class);
        var po=purchasing.create(new PurchasingController.NewOrder(supplier,null,List.of(new PurchasingController.Line(v,5,BigDecimal.valueOf(4)))));
        long id=((Number)po.get("id")).longValue();long line=((Number)((java.util.Map<?,?>)((List<?>)po.get("lines")).getFirst()).get("id")).longValue();
        purchasing.submit(id);
        purchasing.receive(id,new PurchasingController.NewReceipt("delivery-1",List.of(new PurchasingController.ReceiptLine(line,loc,2))));
        purchasing.receive(id,new PurchasingController.NewReceipt("delivery-1",List.of(new PurchasingController.ReceiptLine(line,loc,2))));
        assertThat(db.queryForObject("SELECT on_hand FROM stock_balances WHERE variant_id=? AND location_id=?",Long.class,v,loc)).isEqualTo(2);
        assertThatThrownBy(()->purchasing.cancel(id)).isInstanceOf(ApiSupport.Conflict.class);
        purchasing.receive(id,new PurchasingController.NewReceipt("delivery-2",List.of(new PurchasingController.ReceiptLine(line,loc,3))));
        assertThat(purchasing.one(id).get("status")).isEqualTo("COMPLETED");
        assertThat(stock.reconciliation()).isEmpty();
    }
    @Test void onlyOneConcurrentSaleCanReserveFinalUnit() throws Exception {
        long v=variant(),loc=location();stock.move(v,loc,1,"ADJUSTMENT","TEST",1,"last-unit-"+v,null);
        long l1=salesLine(v),l2=salesLine(v);CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();try{stock.reserve(l1,v,loc,1,30);return true;}catch(ApiSupport.Conflict ex){return false;}});
            var b=pool.submit(()->{start.await();try{stock.reserve(l2,v,loc,1,30);return true;}catch(ApiSupport.Conflict ex){return false;}});
            start.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(db.queryForObject("SELECT reserved FROM stock_balances WHERE variant_id=? AND location_id=?",Long.class,v,loc)).isEqualTo(1);
    }
    long salesLine(long v){long so=db.queryForObject("INSERT INTO sales_orders(customer_id) VALUES(?) RETURNING id",Long.class,customer());return db.queryForObject("INSERT INTO sales_lines(sales_order_id,variant_id,quantity,unit_price) VALUES(?,?,1,10) RETURNING id",Long.class,so,v);}
    @Test @WithMockUser(roles="OWNER") void cancellationReleasesStockAndPartialFulfillmentKeepsOrderOpen(){
        long v=variant(),loc=location();stock.move(v,loc,4,"ADJUSTMENT","TEST",1,"sale-stock-"+v,null);
        var order=sales.create(new SalesController.NewOrder(customer(),List.of(new SalesController.Line(v,1),new SalesController.Line(v,1))));
        long id=((Number)order.get("id")).longValue();var lines=(List<java.util.Map<String,Object>>)order.get("lines");
        sales.reserve(id,new SalesController.Reserve(lines.stream().map(l->new SalesController.Allocation(((Number)l.get("id")).longValue(),loc)).toList()));
        sales.pick(id);sales.pack(id);
        sales.fulfill(id,new SalesController.Fulfill(List.of(((Number)lines.getFirst().get("id")).longValue()),"TRACK-1"));
        assertThat(sales.one(id).get("status")).isEqualTo("PARTIALLY_FULFILLED");
        sales.fulfill(id,new SalesController.Fulfill(List.of(((Number)lines.get(1).get("id")).longValue()),"TRACK-2"));
        assertThat(sales.one(id).get("status")).isEqualTo("FULFILLED");
        var cancelOrder=sales.create(new SalesController.NewOrder(customer(),List.of(new SalesController.Line(v,1))));
        long cancelId=((Number)cancelOrder.get("id")).longValue(),cancelLine=((Number)((java.util.Map<?,?>)((List<?>)cancelOrder.get("lines")).getFirst()).get("id")).longValue();
        sales.reserve(cancelId,new SalesController.Reserve(List.of(new SalesController.Allocation(cancelLine,loc))));sales.cancel(cancelId);
        assertThat(db.queryForObject("SELECT reserved FROM stock_balances WHERE variant_id=? AND location_id=?",Long.class,v,loc)).isZero();
        assertThat(stock.reconciliation()).isEmpty();
    }
    @Test void unauthorizedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/stock")).andExpect(status().isUnauthorized());
        String token=AuthController.token();
        long user=db.queryForObject("INSERT INTO app_users(email,password_hash,role) VALUES(?,?,'AUDITOR') RETURNING id",Long.class,"auditor-"+UUID.randomUUID()+"@example.test","not-used");
        db.update("INSERT INTO app_sessions(token_hash,user_id,expires_at) VALUES(?,?,now()+interval '1 hour')",SecurityConfig.hash(token),user);
        mvc.perform(post("/api/stock/adjustments").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":1,\"locationId\":1,\"delta\":1,\"reason\":\"test\",\"idempotencyKey\":\"test\"}")).andExpect(status().isForbidden());
    }
    @Test void kafkaConsumerIsIdempotent() throws Exception {
        var consumer=new EventConsumers(db,json,mock(JavaMailSender.class));
        long id=System.nanoTime();String event="{\"eventId\":"+id+",\"eventType\":\"inventory.changed\",\"payload\":{}}";
        consumer.operation(event);consumer.operation(event);
        assertThat(db.queryForObject("SELECT count(*) FROM consumed_events WHERE consumer_name='operations' AND event_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void loginIsThrottledAfterRepeatedFailures(){
        String email="throttle-"+UUID.randomUUID()+"@example.test";
        db.update("INSERT INTO app_users(email,password_hash,role) VALUES(?,?,'OWNER')",email,encoder.encode("correct-password-123"));
        for(int attempt=0;attempt<5;attempt++)assertThatThrownBy(()->auth.login(new AuthController.Login(email,"wrong-password"))).isInstanceOf(ApiSupport.Unauthorized.class);
        assertThat(throttle.blocked(email)).isTrue();
        assertThatThrownBy(()->auth.login(new AuthController.Login(email,"correct-password-123"))).isInstanceOf(ApiSupport.Unauthorized.class);
    }
    @Test void ledgerIsAppendOnlyAndLowStockAlertIsDeduplicated(){
        long v=variant(),loc=location();
        db.update("UPDATE products SET reorder_threshold=2 WHERE id=(SELECT product_id FROM product_variants WHERE id=?)",v);
        stock.move(v,loc,3,"ADJUSTMENT","TEST",1,"low-start-"+v,null);
        long movement=db.queryForObject("SELECT id FROM stock_movements WHERE idempotency_key=?",Long.class,"low-start-"+v);
        assertThatThrownBy(()->db.update("UPDATE stock_movements SET delta=4 WHERE id=?",movement)).isInstanceOf(Exception.class);
        stock.reserve(salesLine(v),v,loc,2,30);
        stock.reserve(salesLine(v),v,loc,1,30);
        assertThat(db.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='inventory.low-stock' AND aggregate_id=?",Long.class,v)).isEqualTo(1);
        assertThat(stock.reconciliation()).isEmpty();
    }
}
