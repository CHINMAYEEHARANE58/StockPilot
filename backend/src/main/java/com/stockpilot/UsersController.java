package com.stockpilot;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/users") @PreAuthorize("hasRole('OWNER')")
class UsersController {
    private final JdbcTemplate db;private final PasswordEncoder encoder;
    UsersController(JdbcTemplate db,PasswordEncoder encoder){this.db=db;this.encoder=encoder;}
    record NewUser(String email,String password,String role){}
    @GetMapping List<Map<String,Object>> list(){return db.queryForList("SELECT id,email,role,status,created_at FROM app_users ORDER BY id");}
    @GetMapping("/audit") List<Map<String,Object>> audit(){return db.queryForList("SELECT a.id,a.action,a.entity_type,a.entity_id,a.details,a.created_at,u.email AS actor FROM audit_events a LEFT JOIN app_users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 500");}
    @PostMapping Map<String,Object> create(@RequestBody NewUser r){
        if(r.password()==null||r.password().length()<12||!List.of("OWNER","INVENTORY_MANAGER","PURCHASING_MANAGER","WAREHOUSE_EMPLOYEE","SALES_EMPLOYEE","AUDITOR").contains(r.role()))throw new ApiSupport.Invalid("Invalid role or short password");
        Long id=db.queryForObject("INSERT INTO app_users(email,password_hash,role) VALUES(lower(?),?,?) RETURNING id",Long.class,r.email(),encoder.encode(r.password()),r.role());
        db.update("INSERT INTO audit_events(action,entity_type,entity_id) VALUES('USER_CREATED','USER',?)",id);
        return db.queryForMap("SELECT id,email,role,status FROM app_users WHERE id=?",id);
    }
    @PatchMapping("/{id}/status") void status(@PathVariable long id,@RequestBody Map<String,String> body){
        String status=body.get("status");if(!List.of("ACTIVE","REVOKED").contains(status))throw new ApiSupport.Invalid("Invalid status");
        if(db.update("UPDATE app_users SET status=? WHERE id=?",status,id)==0)throw new ApiSupport.Missing("User not found");
        if("REVOKED".equals(status))db.update("UPDATE app_sessions SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL",id);
        db.update("INSERT INTO audit_events(action,entity_type,entity_id) VALUES('USER_STATUS','USER',?)",id);
    }
}
