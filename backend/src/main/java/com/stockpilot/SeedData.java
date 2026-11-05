package com.stockpilot;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component class SeedData implements ApplicationRunner {
    private final JdbcTemplate db;private final PasswordEncoder encoder;private final StockService stock;
    @Value("${stockpilot.seed}") boolean enabled;
    @Value("${SEED_ADMIN_PASSWORD:}") String password;
    SeedData(JdbcTemplate db,PasswordEncoder encoder,StockService stock){this.db=db;this.encoder=encoder;this.stock=stock;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(!enabled||db.queryForObject("SELECT count(*) FROM app_users",Long.class)>0)return;
        if(password.length()<12)throw new IllegalStateException("Set SEED_ADMIN_PASSWORD to at least 12 characters for first run");
        db.update("INSERT INTO app_users(email,password_hash,role) VALUES('owner@stockpilot.local',?,'OWNER')",encoder.encode(password));
        long category=db.queryForObject("INSERT INTO categories(name) VALUES('Office supplies') RETURNING id",Long.class);
        long product=db.queryForObject("INSERT INTO products(name,category_id,unit,reorder_threshold) VALUES('Notebook A5',?,'EACH',12) RETURNING id",Long.class,category);
        long variant=db.queryForObject("INSERT INTO product_variants(product_id,sku,barcode,label,selling_price,purchase_cost) VALUES(?,'NOTE-A5-BLU','8901234567890','Blue',149.00,78.00) RETURNING id",Long.class,product);
        long product2=db.queryForObject("INSERT INTO products(name,category_id,unit,reorder_threshold) VALUES('Ballpoint pen',?,'EACH',20) RETURNING id",Long.class,category);
        long variant2=db.queryForObject("INSERT INTO product_variants(product_id,sku,barcode,label,selling_price,purchase_cost) VALUES(?,'PEN-BLK-01','8901234567891','Black',25.00,11.00) RETURNING id",Long.class,product2);
        long warehouse=db.queryForObject("INSERT INTO warehouses(name,code) VALUES('Main warehouse','MAIN') RETURNING id",Long.class);
        long location=db.queryForObject("INSERT INTO warehouse_locations(warehouse_id,code) VALUES(?,'A-01') RETURNING id",Long.class,warehouse);
        stock.move(variant,location,45,"OPENING","SEED",0,"seed-notebook",null);
        stock.move(variant2,location,80,"OPENING","SEED",0,"seed-pen",null);
        long supplier=db.queryForObject("INSERT INTO suppliers(name,email,phone,lead_days) VALUES('Acme Stationery','orders@acme.example','+1 555 0100',5) RETURNING id",Long.class);
        db.update("INSERT INTO supplier_products(supplier_id,variant_id) VALUES(?,?),(?,?)",supplier,variant,supplier,variant2);
        long customer=db.queryForObject("INSERT INTO customers(name,email) VALUES('Sample customer','customer@example.com') RETURNING id",Long.class);
        long po=db.queryForObject("INSERT INTO purchase_orders(supplier_id,status,expected_at,submitted_at) VALUES(?,'SUBMITTED',current_date+7,now()) RETURNING id",Long.class,supplier);
        db.update("INSERT INTO purchase_order_lines(purchase_order_id,variant_id,quantity,unit_cost) VALUES(?,?,30,78.00)",po,variant);
        long so=db.queryForObject("INSERT INTO sales_orders(customer_id,status) VALUES(?,'DRAFT') RETURNING id",Long.class,customer);
        db.update("INSERT INTO sales_lines(sales_order_id,variant_id,quantity,unit_price) VALUES(?,?,3,149.00)",so,variant);
    }
}
