package com.stockpilot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service class AuditService {
    private final JdbcTemplate db;AuditService(JdbcTemplate db){this.db=db;}
    Long actorId(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated())return null;
        var ids=db.queryForList("SELECT id FROM app_users WHERE email=?",Long.class,auth.getName());
        return ids.isEmpty()?null:ids.getFirst();
    }
    void record(String action,String type,long id){db.update("INSERT INTO audit_events(actor_id,action,entity_type,entity_id) VALUES(?,?,?,?)",actorId(),action,type,id);}
}
