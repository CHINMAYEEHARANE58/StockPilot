package com.stockpilot;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/sales")
class SalesController {
    private final JdbcTemplate db;private final StockService stock;private final AuditService audit;
    @Value("${stockpilot.reservation-minutes}") int reservationMinutes;
    SalesController(JdbcTemplate db,StockService stock,AuditService audit){this.db=db;this.stock=stock;this.audit=audit;}
    record Line(long variantId,long quantity){}
    record NewOrder(long customerId,List<Line> lines){}
    record Allocation(long lineId,long locationId){}
    record Reserve(List<Allocation> allocations){}
    record Fulfill(List<Long> lineIds,String trackingNumber){}
    record ReturnRequest(long lineId,long locationId,long quantity,String reason,String idempotencyKey){}
    @GetMapping List<Map<String,Object>> list(){return db.queryForList("SELECT so.*,c.name AS customer,(SELECT COALESCE(sum(quantity*unit_price),0) FROM sales_lines WHERE sales_order_id=so.id) AS total FROM sales_orders so JOIN customers c ON c.id=so.customer_id ORDER BY so.id DESC LIMIT 500");}
    @GetMapping("/{id}") Map<String,Object> one(@PathVariable long id){var order=db.queryForMap("SELECT so.*,c.name AS customer FROM sales_orders so JOIN customers c ON c.id=so.customer_id WHERE so.id=?",id);order.put("lines",db.queryForList("SELECT sl.*,v.sku,p.name FROM sales_lines sl JOIN product_variants v ON v.id=sl.variant_id JOIN products p ON p.id=v.product_id WHERE sl.sales_order_id=? ORDER BY sl.id",id));order.put("reservations",db.queryForList("SELECT r.* FROM reservations r JOIN sales_lines sl ON sl.id=r.sales_line_id WHERE sl.sales_order_id=? ORDER BY r.id",id));order.put("shipments",db.queryForList("SELECT * FROM shipments WHERE sales_order_id=? ORDER BY id",id));return order;}
    @PostMapping @Transactional @PreAuthorize("hasAnyRole('OWNER','SALES_EMPLOYEE')") Map<String,Object> create(@RequestBody NewOrder r){
        if(r.lines()==null||r.lines().isEmpty())throw new ApiSupport.Invalid("Add at least one sales line");
        Long id=db.queryForObject("INSERT INTO sales_orders(customer_id) VALUES(?) RETURNING id",Long.class,r.customerId());
        for(Line line:r.lines()){
            StockService.positive(line.quantity());
            var prices=db.queryForList("SELECT selling_price FROM product_variants v JOIN products p ON p.id=v.product_id WHERE v.id=? AND v.active AND p.active",line.variantId());
            if(prices.isEmpty())throw new ApiSupport.Invalid("Product variant is inactive or missing");
            db.update("INSERT INTO sales_lines(sales_order_id,variant_id,quantity,unit_price) VALUES(?,?,?,?)",id,line.variantId(),line.quantity(),prices.getFirst().get("selling_price"));
        }audit.record("SALES_CREATED","SALES_ORDER",id);return one(id);
    }
    @PostMapping("/{id}/reserve") @Transactional @PreAuthorize("hasAnyRole('OWNER','SALES_EMPLOYEE','INVENTORY_MANAGER')") Map<String,Object> reserve(@PathVariable long id,@RequestBody Reserve r){
        var order=db.queryForMap("SELECT status FROM sales_orders WHERE id=? FOR UPDATE",id);
        if(!List.of("DRAFT","STOCK_REQUIRED").contains(order.get("status")))throw new ApiSupport.Conflict("Order is already in fulfillment");
        var lines=db.queryForList("SELECT id,variant_id,quantity,fulfilled_quantity FROM sales_lines WHERE sales_order_id=? ORDER BY id",id);
        if(r.allocations()==null||r.allocations().size()!=lines.size())throw new ApiSupport.Invalid("Allocate each order line exactly once");
        var lineById=new java.util.HashMap<Long,Map<String,Object>>();
        for(var line:lines)lineById.put(((Number)line.get("id")).longValue(),line);
        var allocated=new HashSet<Long>();
        for(Allocation a:r.allocations())if(!allocated.add(a.lineId())||!lineById.containsKey(a.lineId()))throw new ApiSupport.Invalid("Duplicate or unrelated line allocation");
        for(Allocation a:r.allocations().stream().sorted(java.util.Comparator.comparingLong((Allocation x)->((Number)lineById.get(x.lineId()).get("variant_id")).longValue()).thenComparingLong(Allocation::locationId)).toList()){
            var line=lineById.get(a.lineId());
            long qty=((Number)line.get("quantity")).longValue()-((Number)line.get("fulfilled_quantity")).longValue();
            stock.reserve(a.lineId(),((Number)line.get("variant_id")).longValue(),a.locationId(),qty,reservationMinutes);
        }
        db.update("UPDATE sales_orders SET status='RESERVED',submitted_at=COALESCE(submitted_at,now()) WHERE id=?",id);
        audit.record("SALES_RESERVED","SALES_ORDER",id);
        return one(id);
    }
    @PostMapping("/{id}/pick") @Transactional @PreAuthorize("hasAnyRole('OWNER','WAREHOUSE_EMPLOYEE')") Map<String,Object> pick(@PathVariable long id){transition(id,"RESERVED","PICKING");return one(id);}
    @PostMapping("/{id}/pack") @Transactional @PreAuthorize("hasAnyRole('OWNER','WAREHOUSE_EMPLOYEE')") Map<String,Object> pack(@PathVariable long id){transition(id,"PICKING","PACKED");return one(id);}
    void transition(long id,String from,String to){if(db.update("UPDATE sales_orders SET status=? WHERE id=? AND status=? AND NOT EXISTS(SELECT 1 FROM reservations r JOIN sales_lines l ON l.id=r.sales_line_id WHERE l.sales_order_id=? AND r.status='ACTIVE' AND r.expires_at<now())",to,id,from,id)!=1)throw new ApiSupport.Conflict("Invalid order state or expired reservation");audit.record("SALES_"+to,"SALES_ORDER",id);}
    @PostMapping("/{id}/fulfill") @Transactional @PreAuthorize("hasAnyRole('OWNER','WAREHOUSE_EMPLOYEE')") Map<String,Object> fulfill(@PathVariable long id,@RequestBody Fulfill r){
        var order=db.queryForMap("SELECT status FROM sales_orders WHERE id=? FOR UPDATE",id);
        if(!List.of("PACKED","PARTIALLY_FULFILLED").contains(order.get("status")))throw new ApiSupport.Conflict("Order must be packed first");
        if(r.lineIds()==null||r.lineIds().isEmpty()||new HashSet<>(r.lineIds()).size()!=r.lineIds().size())throw new ApiSupport.Invalid("Select unique lines to fulfill");
        Long shipment=db.queryForObject("INSERT INTO shipments(sales_order_id,tracking_number,status,shipped_at) VALUES(?,?,'SHIPPED',now()) RETURNING id",Long.class,id,r.trackingNumber());
        for(Long lineId:r.lineIds()){
            var line=db.queryForMap("SELECT quantity,fulfilled_quantity FROM sales_lines WHERE id=? AND sales_order_id=? FOR UPDATE",lineId,id);
            if(((Number)line.get("fulfilled_quantity")).longValue()!=0)throw new ApiSupport.Conflict("Line already fulfilled");
            var reservations=db.queryForList("SELECT id,expires_at FROM reservations WHERE sales_line_id=? AND status='ACTIVE' FOR UPDATE",lineId);
            if(reservations.size()!=1 || ((java.sql.Timestamp)reservations.getFirst().get("expires_at")).toInstant().isBefore(java.time.Instant.now()))throw new ApiSupport.Conflict("Reservation missing or expired");
            stock.consume(((Number)reservations.getFirst().get("id")).longValue(),null);
            db.update("UPDATE sales_lines SET fulfilled_quantity=quantity WHERE id=?",lineId);
        }
        Long remaining=db.queryForObject("SELECT count(*) FROM sales_lines WHERE sales_order_id=? AND fulfilled_quantity<quantity",Long.class,id);
        db.update("UPDATE sales_orders SET status=?,fulfilled_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?",remaining==0?"FULFILLED":"PARTIALLY_FULFILLED",remaining==0,id);
        stock.event("sales.fulfilled",id,Map.of("salesOrderId",id,"shipmentId",shipment,"complete",remaining==0));
        audit.record("SALES_SHIPPED","SALES_ORDER",id);
        return one(id);
    }
    @PostMapping("/{id}/cancel") @Transactional @PreAuthorize("hasAnyRole('OWNER','SALES_EMPLOYEE')") Map<String,Object> cancel(@PathVariable long id){
        var order=db.queryForMap("SELECT status FROM sales_orders WHERE id=? FOR UPDATE",id);
        if(List.of("FULFILLED","PARTIALLY_FULFILLED","CANCELLED").contains(order.get("status")))throw new ApiSupport.Conflict("Fulfilled sales require a return");
        var ids=db.queryForList("SELECT r.id FROM reservations r JOIN sales_lines l ON l.id=r.sales_line_id WHERE l.sales_order_id=? AND r.status='ACTIVE' ORDER BY r.id",Long.class,id);
        for(Long reservation:ids)stock.release(reservation,"CANCELLED");
        db.update("UPDATE sales_orders SET status='CANCELLED',cancelled_at=now() WHERE id=?",id);audit.record("SALES_CANCELLED","SALES_ORDER",id);return one(id);
    }
    @PostMapping("/{id}/returns") @Transactional @PreAuthorize("hasAnyRole('OWNER','SALES_EMPLOYEE','WAREHOUSE_EMPLOYEE')") Map<String,Object> returned(@PathVariable long id,@RequestBody ReturnRequest r){
        if(r.reason()==null||r.reason().isBlank()||r.idempotencyKey()==null||r.idempotencyKey().isBlank())throw new ApiSupport.Invalid("Reason and idempotency key required");
        StockService.positive(r.quantity());
        var line=db.queryForMap("SELECT variant_id,fulfilled_quantity FROM sales_lines WHERE id=? AND sales_order_id=? FOR UPDATE",r.lineId(),id);
        var priorRequest=db.queryForList("SELECT id,sales_line_id,location_id,quantity FROM returns WHERE idempotency_key=?",r.idempotencyKey());
        if(!priorRequest.isEmpty()){
            var prior=priorRequest.getFirst();
            if(((Number)prior.get("sales_line_id")).longValue()!=r.lineId()||((Number)prior.get("location_id")).longValue()!=r.locationId()||((Number)prior.get("quantity")).longValue()!=r.quantity())throw new ApiSupport.Conflict("Idempotency key used for a different return");
            return Map.of("returnId",prior.get("id"));
        }
        long prior=db.queryForObject("SELECT COALESCE(sum(quantity),0) FROM returns WHERE sales_line_id=?",Long.class,r.lineId());
        if(prior+r.quantity()>((Number)line.get("fulfilled_quantity")).longValue())throw new ApiSupport.Conflict("Return exceeds fulfilled quantity");
        Long returnId=db.queryForObject("INSERT INTO returns(sales_line_id,location_id,quantity,reason,idempotency_key) VALUES(?,?,?,?,?) RETURNING id",Long.class,r.lineId(),r.locationId(),r.quantity(),r.reason(),r.idempotencyKey());
        stock.move(((Number)line.get("variant_id")).longValue(),r.locationId(),r.quantity(),"RETURN","RETURN",returnId,r.idempotencyKey(),null);
        audit.record("SALES_RETURNED","SALES_ORDER",id);
        return Map.of("returnId",returnId);
    }
    @PatchMapping("/shipments/{shipmentId}/status") @Transactional @PreAuthorize("hasAnyRole('OWNER','WAREHOUSE_EMPLOYEE','SALES_EMPLOYEE')") Map<String,Object> shipmentStatus(@PathVariable long shipmentId,@RequestBody Map<String,String> body){
        String status=body.get("status");if(!List.of("IN_TRANSIT","DELIVERED","EXCEPTION").contains(status))throw new ApiSupport.Invalid("Invalid shipment status");
        if(db.update("UPDATE shipments SET status=? WHERE id=? AND status IN ('SHIPPED','IN_TRANSIT','EXCEPTION')",status,shipmentId)==0)throw new ApiSupport.Conflict("Shipment cannot move to that status");
        audit.record("SHIPMENT_"+status,"SHIPMENT",shipmentId);
        return db.queryForMap("SELECT * FROM shipments WHERE id=?",shipmentId);
    }
}
