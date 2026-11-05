package com.stockpilot;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
class AuthController {
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final JavaMailSender mail;private final LoginThrottle throttle;
    @Value("${stockpilot.notifications-from}") String from;
    AuthController(JdbcTemplate db,PasswordEncoder encoder,JavaMailSender mail,LoginThrottle throttle){this.db=db;this.encoder=encoder;this.mail=mail;this.throttle=throttle;}
    record Login(String email,String password) {} record PasswordReset(String token,String password) {}
    static String token(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    @PostMapping("/login") @Transactional Map<String,Object> login(@RequestBody Login request) {
        if(request.email()==null||throttle.blocked(request.email()))throw new ApiSupport.Unauthorized("Invalid credentials or temporarily locked account");
        var users=db.queryForList("SELECT id,password_hash,role FROM app_users WHERE lower(email)=lower(?) AND status='ACTIVE'",request.email());
        if(users.isEmpty() || request.password()==null || !encoder.matches(request.password(),(String)users.getFirst().get("password_hash"))){throttle.failed(request.email());throw new ApiSupport.Unauthorized("Invalid credentials or temporarily locked account");}
        throttle.clear(request.email());
        String token=token(); long id=((Number)users.getFirst().get("id")).longValue();
        db.update("INSERT INTO app_sessions(token_hash,user_id,expires_at) VALUES(?,?,now()+interval '12 hours')",SecurityConfig.hash(token),id);
        db.update("INSERT INTO audit_events(actor_id,action,entity_type,entity_id) VALUES(?,'LOGIN','USER',?)",id,id);
        return Map.of("token",token,"role",users.getFirst().get("role"),"email",request.email(),"expiresAt",Instant.now().plusSeconds(43200).toString());
    }
    @PostMapping("/logout") void logout(@RequestHeader("Authorization") String header) {db.update("UPDATE app_sessions SET revoked_at=now() WHERE token_hash=?",SecurityConfig.hash(header.substring(7)));}
    @PostMapping("/reset/request") void requestReset(@RequestBody Map<String,String> body) {
        String email=body.get("email"); var ids=db.query("SELECT id FROM app_users WHERE lower(email)=lower(?) AND status='ACTIVE'",(rs,n)->rs.getLong(1),email);
        if(ids.isEmpty()) return;
        String token=token();db.update("UPDATE app_users SET reset_token_hash=?,reset_expires_at=now()+interval '30 minutes' WHERE id=?",SecurityConfig.hash(token),ids.getFirst());
        SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject("StockPilot password reset");message.setText("Reset token: "+token+"\nExpires in 30 minutes.");mail.send(message);
    }
    @PostMapping("/reset/confirm") @Transactional void reset(@RequestBody PasswordReset request){
        if(request.password()==null || request.password().length()<12)throw new ApiSupport.Invalid("Password must have at least 12 characters");
        var ids=db.query("SELECT id FROM app_users WHERE reset_token_hash=? AND reset_expires_at>now()",(rs,n)->rs.getLong(1),SecurityConfig.hash(request.token()));
        if(ids.isEmpty())throw new ApiSupport.Invalid("Invalid or expired reset token");
        long id=ids.getFirst();db.update("UPDATE app_users SET password_hash=?,reset_token_hash=NULL,reset_expires_at=NULL WHERE id=?",encoder.encode(request.password()),id);
        db.update("UPDATE app_sessions SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL",id);
        db.update("INSERT INTO audit_events(actor_id,action,entity_type,entity_id) VALUES(?,'PASSWORD_RESET','USER',?)",id,id);
    }
    @GetMapping("/me") Map<String,Object> me(Authentication auth){return db.queryForMap("SELECT id,email,role,status FROM app_users WHERE email=?",auth.getName());}
}
