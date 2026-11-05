package com.stockpilot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api")
class CatalogController {
    private final JdbcTemplate db;private final ProductRepository products;private final StockService stock;
    CatalogController(JdbcTemplate db,ProductRepository products,StockService stock){this.db=db;this.products=products;this.stock=stock;}
    record NewProduct(String name,Long categoryId,String unit,long reorderThreshold){}
    record NewVariant(long productId,String sku,String barcode,String label,BigDecimal sellingPrice,BigDecimal purchaseCost){}
    record NewWarehouse(String name,String code){}
    record NewLocation(long warehouseId,String code){}
    record NewSupplier(String name,String email,String phone,int leadDays){}
    record NewCustomer(String name,String email,String phone,String address){}
    static void money(BigDecimal amount){if(amount==null||amount.signum()<0||amount.scale()>2||amount.precision()>14)throw new ApiSupport.Invalid("Invalid monetary amount");}
    @GetMapping("/products") List<Map<String,Object>> products(@RequestParam(defaultValue="") String search,@RequestParam(required=false) Boolean active){
        return db.queryForList("SELECT p.id,p.name,p.unit,p.active,p.reorder_threshold,c.name AS category,v.id AS variant_id,v.sku,v.barcode,v.label,v.selling_price,v.purchase_cost,v.active AS variant_active FROM products p LEFT JOIN categories c ON c.id=p.category_id LEFT JOIN product_variants v ON v.product_id=p.id WHERE (lower(p.name) LIKE lower(?) OR lower(COALESCE(v.sku,'')) LIKE lower(?) OR lower(COALESCE(v.barcode,'')) LIKE lower(?)) AND (?::boolean IS NULL OR p.active=?::boolean) ORDER BY p.name LIMIT 500", "%"+search+"%","%"+search+"%","%"+search+"%",active,active);
    }
    @PostMapping("/products") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") Product createProduct(@RequestBody NewProduct r){
        if(r.name()==null||r.name().isBlank()||r.reorderThreshold()<0)throw new ApiSupport.Invalid("Invalid product");
        return products.save(new Product(r.name().trim(),r.categoryId(),r.unit()==null?"EACH":r.unit(),r.reorderThreshold()));
    }
    @PostMapping("/variants") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") Map<String,Object> variant(@RequestBody NewVariant r){
        if(r.sku()==null||r.sku().isBlank())throw new ApiSupport.Invalid("Invalid SKU");money(r.sellingPrice());money(r.purchaseCost());
        Long id=db.queryForObject("INSERT INTO product_variants(product_id,sku,barcode,label,selling_price,purchase_cost) VALUES(?,?,?,?,?,?) RETURNING id",Long.class,r.productId(),r.sku().trim(),r.barcode(),r.label()==null?"Standard":r.label(),r.sellingPrice(),r.purchaseCost());
        return db.queryForMap("SELECT * FROM product_variants WHERE id=?",id);
    }
    @PatchMapping("/products/{id}/status") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") void productStatus(@PathVariable long id,@RequestBody Map<String,Boolean> body){if(db.update("UPDATE products SET active=? WHERE id=?",body.get("active"),id)==0)throw new ApiSupport.Missing("Product not found");}
    @PatchMapping("/variants/{id}/status") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") void variantStatus(@PathVariable long id,@RequestBody Map<String,Boolean> body){if(db.update("UPDATE product_variants SET active=? WHERE id=?",body.get("active"),id)==0)throw new ApiSupport.Missing("Variant not found");}
    @GetMapping("/categories") List<Map<String,Object>> categories(){return db.queryForList("SELECT * FROM categories ORDER BY name");}
    @PostMapping("/categories") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") Map<String,Object> category(@RequestBody Map<String,String> r){Long id=db.queryForObject("INSERT INTO categories(name) VALUES(?) RETURNING id",Long.class,r.get("name"));return db.queryForMap("SELECT * FROM categories WHERE id=?",id);}
    @GetMapping("/warehouses") List<Map<String,Object>> warehouses(){return db.queryForList("SELECT w.*,l.id AS location_id,l.code AS location_code FROM warehouses w LEFT JOIN warehouse_locations l ON l.warehouse_id=w.id ORDER BY w.name,l.code");}
    @PostMapping("/warehouses") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") Map<String,Object> warehouse(@RequestBody NewWarehouse r){Long id=db.queryForObject("INSERT INTO warehouses(name,code) VALUES(?,?) RETURNING id",Long.class,r.name(),r.code());return db.queryForMap("SELECT * FROM warehouses WHERE id=?",id);}
    @PostMapping("/locations") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") Map<String,Object> location(@RequestBody NewLocation r){Long id=db.queryForObject("INSERT INTO warehouse_locations(warehouse_id,code) VALUES(?,?) RETURNING id",Long.class,r.warehouseId(),r.code());return db.queryForMap("SELECT * FROM warehouse_locations WHERE id=?",id);}
    @GetMapping("/suppliers") List<Map<String,Object>> suppliers(){return db.queryForList("SELECT s.*,COUNT(DISTINCT po.id) AS purchase_orders,COUNT(DISTINCT CASE WHEN po.completed_at::date<=po.expected_at THEN po.id END) AS on_time_orders FROM suppliers s LEFT JOIN purchase_orders po ON po.supplier_id=s.id GROUP BY s.id ORDER BY s.name");}
    @GetMapping("/suppliers/{id}") Map<String,Object> supplierDetails(@PathVariable long id){var supplier=db.queryForMap("SELECT * FROM suppliers WHERE id=?",id);supplier.put("products",db.queryForList("SELECT v.id,v.sku,p.name FROM supplier_products sp JOIN product_variants v ON v.id=sp.variant_id JOIN products p ON p.id=v.product_id WHERE sp.supplier_id=? ORDER BY p.name",id));supplier.put("purchaseHistory",db.queryForList("SELECT id,status,created_at,completed_at FROM purchase_orders WHERE supplier_id=? ORDER BY id DESC LIMIT 100",id));return supplier;}
    @PostMapping("/suppliers") @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER')") Map<String,Object> supplier(@RequestBody NewSupplier r){Long id=db.queryForObject("INSERT INTO suppliers(name,email,phone,lead_days) VALUES(?,?,?,?) RETURNING id",Long.class,r.name(),r.email(),r.phone(),r.leadDays());return db.queryForMap("SELECT * FROM suppliers WHERE id=?",id);}
    @PostMapping("/suppliers/{id}/variants/{variantId}") @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER')") void supplierProduct(@PathVariable long id,@PathVariable long variantId){db.update("INSERT INTO supplier_products(supplier_id,variant_id) VALUES(?,?) ON CONFLICT DO NOTHING",id,variantId);}
    @PatchMapping("/suppliers/{id}/status") @PreAuthorize("hasAnyRole('OWNER','PURCHASING_MANAGER')") void supplierStatus(@PathVariable long id,@RequestBody Map<String,String> body){String status=body.get("status");if(!List.of("ACTIVE","INACTIVE").contains(status))throw new ApiSupport.Invalid("Invalid status");db.update("UPDATE suppliers SET status=? WHERE id=?",status,id);}
    @GetMapping("/customers") List<Map<String,Object>> customers(){return db.queryForList("SELECT * FROM customers ORDER BY name LIMIT 500");}
    @PostMapping("/customers") @PreAuthorize("hasAnyRole('OWNER','SALES_EMPLOYEE')") Map<String,Object> customer(@RequestBody NewCustomer r){Long id=db.queryForObject("INSERT INTO customers(name,email,phone,address) VALUES(?,?,?,?) RETURNING id",Long.class,r.name(),r.email(),r.phone(),r.address());return db.queryForMap("SELECT * FROM customers WHERE id=?",id);}
    @GetMapping("/stock") List<Map<String,Object>> balances(){return stock.balances();}
    @GetMapping("/stock/{variantId}/movements") List<Map<String,Object>> movements(@PathVariable long variantId){return stock.movements(variantId);}
    @GetMapping("/stock/reconciliation") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER','AUDITOR')") List<Map<String,Object>> reconciliation(){return stock.reconciliation();}
    record Adjustment(long variantId,long locationId,long delta,String reason,String idempotencyKey){}
    @PostMapping("/stock/adjustments") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") void adjust(@RequestBody Adjustment r){if(r.reason()==null||r.reason().isBlank()||r.idempotencyKey()==null||r.idempotencyKey().isBlank())throw new ApiSupport.Invalid("Reason and key required");stock.move(r.variantId(),r.locationId(),r.delta(),"ADJUSTMENT",r.reason(),0,r.idempotencyKey(),null);}
    record Transfer(long variantId,long sourceLocationId,long targetLocationId,long quantity,String idempotencyKey){}
    @PostMapping("/stock/transfers") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER','WAREHOUSE_EMPLOYEE')") void transfer(@RequestBody Transfer r){if(r.idempotencyKey()==null||r.idempotencyKey().isBlank())throw new ApiSupport.Invalid("Idempotency key required");stock.transfer(r.variantId(),r.sourceLocationId(),r.targetLocationId(),r.quantity(),r.idempotencyKey(),null);}
    @PostMapping(value="/products/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER')") @Transactional Map<String,Integer> importCsv(@RequestPart MultipartFile file) throws Exception {
        if(file.getSize()>2_000_000)throw new ApiSupport.Invalid("CSV exceeds 2 MB");
        int count=0;try(var reader=new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8);var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)){
            if(!parser.getHeaderNames().equals(List.of("name","sku","barcode","selling_price","purchase_cost")))throw new ApiSupport.Invalid("Expected CSV columns: name,sku,barcode,selling_price,purchase_cost");
            for(var row:parser){
                if(row.size()!=5||row.toString().length()>4096)throw new ApiSupport.Invalid("Invalid CSV row");
                for(String value:row)if(!value.isEmpty()&&"=+-@".indexOf(value.charAt(0))>=0)throw new ApiSupport.Invalid("Spreadsheet formula input rejected");
                Product p=products.save(new Product(row.get(0),null,"EACH",0));variant(new NewVariant(p.id,row.get(1),row.get(2),"Standard",new BigDecimal(row.get(3)),new BigDecimal(row.get(4))));count++;
            }
        }return Map.of("imported",count);
    }
}
