package com.stockpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service class FailedStockAlertService {
    private final JdbcTemplate db;private final ObjectMapper json;private final AuditService audit;
    FailedStockAlertService(JdbcTemplate db,ObjectMapper json,AuditService audit){this.db=db;this.json=json;this.audit=audit;}
    @Transactional(propagation=Propagation.REQUIRES_NEW) void record(String reason){
        Long id=db.queryForObject("INSERT INTO failed_stock_updates(actor_id,reason) VALUES(?,?) RETURNING id",Long.class,audit.actorId(),reason);
        try{db.update("INSERT INTO outbox_events(event_type,aggregate_id,payload) VALUES('inventory.update-failed',?,?::jsonb)",id,json.writeValueAsString(Map.of("failureId",id,"reason",reason)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
