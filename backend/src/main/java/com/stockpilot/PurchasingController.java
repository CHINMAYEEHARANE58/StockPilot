package com.stockpilot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/purchases")
class PurchasingController {
    private final JdbcTemplate db;private final StockService stock;private final AuditService audit;
    PurchasingController(JdbcTemplate db,StockService stock,AuditService audit){this.db=db;this.stock=stock;this.audit=audit;}
    record Line(long variantId,long quantity,BigDecimal unitCost){}
    record NewOrder(long supplierId,LocalDate expectedAt,List<Line> lines){}
    record ReceiptLine(long purchaseLineId,long locationId,long quantity){}
    record NewReceipt(String externalRef,List<ReceiptLine> lines){}
    @GetMapping List<Map<String,Object>> list(){return db.queryForList("SELECT po.*,s.name AS supplier,(SELECT COALESCE(sum(quantity*unit_cost),0) FROM purchase_order_lines WHERE purchase_order_id=po.id) AS total FROM purchase_orders po JOIN suppliers s ON s.id=po.supplier_id ORDER BY po.id DESC LIMIT 500");}
    @GetMapping("/{id}") Map<String,Object> one(@PathVariable long id){var order=db.queryForMap("SELECT po.*,s.name AS supplier FROM purchase_orders po JOIN suppliers s ON s.id=po.supplier_id WHERE po.id=?",id);order.put("lines",db.queryForList("SELECT l.*,v.sku,p.name FROM purchase_order_lines l JOIN product_variants v ON v.id=l.variant_id JOIN products p ON p.id=v.product_id WHERE purchase_order_id=? ORDER BY l.id",id));order.put("receipts",db.queryForList("SELECT * FROM goods_receipts WHERE purchase_order_id=? ORDER BY id",id));return order;}
    @PostMapping @Transactional @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER')") Map<String,Object> create(@RequestBody NewOrder r){
        if(r.lines()==null||r.lines().isEmpty())throw new ApiSupport.Invalid("Add at least one purchase line");
        Integer active=db.queryForObject("SELECT count(*) FROM suppliers WHERE id=? AND status='ACTIVE'",Integer.class,r.supplierId());if(active==0)throw new ApiSupport.Invalid("Supplier is inactive or missing");
        Long id=db.queryForObject("INSERT INTO purchase_orders(supplier_id,expected_at) VALUES(?,?) RETURNING id",Long.class,r.supplierId(),r.expectedAt());
        for(Line line:r.lines()){
            StockService.positive(line.quantity());CatalogController.money(line.unitCost());
            db.update("INSERT INTO purchase_order_lines(purchase_order_id,variant_id,quantity,unit_cost) VALUES(?,?,?,?)",id,line.variantId(),line.quantity(),line.unitCost());
        }audit.record("PURCHASE_CREATED","PURCHASE_ORDER",id);return one(id);
    }
    @PostMapping("/{id}/submit") @Transactional @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER')") Map<String,Object> submit(@PathVariable long id){if(db.update("UPDATE purchase_orders SET status='SUBMITTED',submitted_at=now() WHERE id=? AND status='DRAFT'",id)!=1)throw new ApiSupport.Conflict("Only draft orders can be submitted");audit.record("PURCHASE_SUBMITTED","PURCHASE_ORDER",id);return one(id);}
    @PostMapping("/{id}/cancel") @Transactional @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER')") Map<String,Object> cancel(@PathVariable long id){if(db.update("UPDATE purchase_orders SET status='CANCELLED' WHERE id=? AND status IN ('DRAFT','SUBMITTED') AND NOT EXISTS(SELECT 1 FROM purchase_order_lines WHERE purchase_order_id=? AND received_quantity>0)",id,id)!=1)throw new ApiSupport.Conflict("Cannot cancel an order with receipts");audit.record("PURCHASE_CANCELLED","PURCHASE_ORDER",id);return one(id);}
    @PostMapping("/{id}/receipts") @Transactional @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER','WAREHOUSE_EMPLOYEE')") Map<String,Object> receive(@PathVariable long id,@RequestBody NewReceipt r){
        if(r.externalRef()==null||r.externalRef().isBlank()||r.lines()==null||r.lines().isEmpty())throw new ApiSupport.Invalid("Receipt reference and lines required");
        var po=db.queryForMap("SELECT status FROM purchase_orders WHERE id=? FOR UPDATE",id);
        var existing=db.queryForList("SELECT id FROM goods_receipts WHERE purchase_order_id=? AND external_ref=?",Long.class,id,r.externalRef());
        if(!existing.isEmpty())return Map.of("receiptId",existing.getFirst(),"order",one(id));
        if(!List.of("SUBMITTED","PARTIALLY_RECEIVED").contains(po.get("status")))throw new ApiSupport.Conflict("Order is not receivable");
        Long receipt=db.queryForObject("INSERT INTO goods_receipts(purchase_order_id,external_ref) VALUES(?,?) RETURNING id",Long.class,id,r.externalRef());
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(ReceiptLine line:r.lines()){
            StockService.positive(line.quantity());
            if(!seen.add(line.purchaseLineId()+":"+line.locationId()))throw new ApiSupport.Invalid("Duplicate receipt line and location");
            var pl=db.queryForMap("SELECT variant_id,quantity,received_quantity FROM purchase_order_lines WHERE id=? AND purchase_order_id=? FOR UPDATE",line.purchaseLineId(),id);
            long remaining=((Number)pl.get("quantity")).longValue()-((Number)pl.get("received_quantity")).longValue();
            if(line.quantity()>remaining)throw new ApiSupport.Conflict("Receipt exceeds remaining purchase quantity");
            db.update("INSERT INTO goods_receipt_lines(receipt_id,purchase_line_id,location_id,quantity) VALUES(?,?,?,?)",receipt,line.purchaseLineId(),line.locationId(),line.quantity());
            db.update("UPDATE purchase_order_lines SET received_quantity=received_quantity+? WHERE id=?",line.quantity(),line.purchaseLineId());
            stock.move(((Number)pl.get("variant_id")).longValue(),line.locationId(),line.quantity(),"PURCHASE_RECEIPT","GOODS_RECEIPT",receipt,"receipt-"+receipt+"-line-"+line.purchaseLineId()+"-loc-"+line.locationId(),null);
        }
        Long remaining=db.queryForObject("SELECT count(*) FROM purchase_order_lines WHERE purchase_order_id=? AND received_quantity<quantity",Long.class,id);
        db.update("UPDATE purchase_orders SET status=?,completed_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?",remaining==0?"COMPLETED":"PARTIALLY_RECEIVED",remaining==0,id);
        stock.event("purchase.received",id,Map.of("purchaseOrderId",id,"receiptId",receipt));
        audit.record("PURCHASE_RECEIVED","PURCHASE_ORDER",id);
        return Map.of("receiptId",receipt,"order",one(id));
    }
}
