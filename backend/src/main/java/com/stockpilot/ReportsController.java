package com.stockpilot;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reports") @PreAuthorize("hasAnyRole('OWNER','INVENTORY_MANAGER','PURCHASING_MANAGER','AUDITOR')")
class ReportsController {
    private final JdbcTemplate db;ReportsController(JdbcTemplate db){this.db=db;}
    @GetMapping("/{name}") List<Map<String,Object>> report(@PathVariable String name){return switch(name){
        case "valuation" -> db.queryForList("SELECT w.name AS warehouse,v.sku,p.name AS product,sum(b.on_hand) AS units,sum(b.on_hand*v.purchase_cost) AS value FROM stock_balances b JOIN product_variants v ON v.id=b.variant_id JOIN products p ON p.id=v.product_id JOIN warehouse_locations l ON l.id=b.location_id JOIN warehouses w ON w.id=l.warehouse_id GROUP BY w.name,v.sku,p.name ORDER BY w.name,p.name");
        case "low-stock" -> db.queryForList("SELECT v.id AS variant_id,v.sku,p.name AS product,p.reorder_threshold,COALESCE(sum(b.on_hand-b.reserved),0) AS available,GREATEST(p.reorder_threshold-COALESCE(sum(b.on_hand-b.reserved),0),0) AS suggested_order FROM product_variants v JOIN products p ON p.id=v.product_id LEFT JOIN stock_balances b ON b.variant_id=v.id GROUP BY v.id,v.sku,p.name,p.reorder_threshold HAVING COALESCE(sum(b.on_hand-b.reserved),0)<=p.reorder_threshold ORDER BY available");
        case "movements" -> db.queryForList("SELECT m.id,m.created_at,v.sku,w.name AS warehouse,l.code AS location,m.delta,m.movement_type,m.reference_type,m.reference_id FROM stock_movements m JOIN product_variants v ON v.id=m.variant_id JOIN warehouse_locations l ON l.id=m.location_id JOIN warehouses w ON w.id=l.warehouse_id ORDER BY m.id DESC LIMIT 1000");
        case "top-selling" -> db.queryForList("SELECT v.sku,p.name AS product,sum(l.fulfilled_quantity) AS units,sum(l.fulfilled_quantity*l.unit_price) AS revenue FROM sales_lines l JOIN product_variants v ON v.id=l.variant_id JOIN products p ON p.id=v.product_id GROUP BY v.sku,p.name ORDER BY units DESC LIMIT 100");
        case "purchases" -> db.queryForList("SELECT po.id,s.name AS supplier,po.status,po.expected_at,po.completed_at,sum(l.quantity*l.unit_cost) AS ordered_value,sum(l.received_quantity*l.unit_cost) AS received_value FROM purchase_orders po JOIN suppliers s ON s.id=po.supplier_id JOIN purchase_order_lines l ON l.purchase_order_id=po.id GROUP BY po.id,s.name ORDER BY po.id DESC");
        case "processing-time" -> db.queryForList("SELECT id,status,submitted_at,fulfilled_at,EXTRACT(EPOCH FROM (fulfilled_at-submitted_at))/3600 AS hours_to_fulfill FROM sales_orders WHERE submitted_at IS NOT NULL ORDER BY id DESC LIMIT 500");
        case "utilization" -> db.queryForList("SELECT w.name AS warehouse,l.code AS location,COUNT(DISTINCT b.variant_id) AS occupied_skus,COALESCE(sum(b.on_hand),0) AS units FROM warehouse_locations l JOIN warehouses w ON w.id=l.warehouse_id LEFT JOIN stock_balances b ON b.location_id=l.id GROUP BY w.name,l.code ORDER BY w.name,l.code");
        case "pending-deliveries" -> db.queryForList("SELECT po.id,s.name AS supplier,po.expected_at,po.status,sum(l.quantity-l.received_quantity) AS remaining_units FROM purchase_orders po JOIN suppliers s ON s.id=po.supplier_id JOIN purchase_order_lines l ON l.purchase_order_id=po.id WHERE po.status IN ('SUBMITTED','PARTIALLY_RECEIVED') GROUP BY po.id,s.name ORDER BY po.expected_at NULLS LAST");
        default -> throw new ApiSupport.Missing("Unknown report");
    };}
    static String safe(Object value){String s=value==null?"":value.toString();if(!s.isEmpty()&&"=+-@".indexOf(s.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    @GetMapping(value="/{name}.csv",produces="text/csv") void csv(@PathVariable String name,HttpServletResponse response) throws IOException {
        response.setHeader("Content-Disposition","attachment; filename=\""+name+".csv\"");var rows=report(name);if(rows.isEmpty())return;
        var writer=response.getWriter();List<String> columns=new ArrayList<>(rows.getFirst().keySet());writer.println(String.join(",",columns));for(var row:rows)writer.println(String.join(",",columns.stream().map(c->safe(row.get(c))).toList()));
    }
    @GetMapping(value="/{name}.pdf",produces="application/pdf") void pdf(@PathVariable String name,HttpServletResponse response) throws IOException {
        response.setHeader("Content-Disposition","attachment; filename=\""+name+".pdf\"");var rows=report(name);
        var document=new Document();PdfWriter.getInstance(document,response.getOutputStream());document.open();document.add(new Paragraph("StockPilot · "+name));document.add(new Paragraph("Generated "+java.time.Instant.now()));
        if(!rows.isEmpty()){var columns=new ArrayList<>(rows.getFirst().keySet());PdfPTable table=new PdfPTable(columns.size());table.setWidthPercentage(100);for(String column:columns)table.addCell(column);for(var row:rows)for(String column:columns)table.addCell(String.valueOf(row.get(column)));document.add(table);}document.close();
    }
}
