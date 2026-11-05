package com.stockpilot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service class LoginThrottle {
    private final JdbcTemplate db;LoginThrottle(JdbcTemplate db){this.db=db;}
    boolean blocked(String email){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM login_attempts WHERE email=lower(?) AND locked_until>now())",Boolean.class,email));}
    @Transactional(propagation=Propagation.REQUIRES_NEW) void failed(String email){
        db.update("INSERT INTO login_attempts(email,failure_count,locked_until) VALUES(lower(?),1,NULL) ON CONFLICT(email) DO UPDATE SET failure_count=CASE WHEN login_attempts.updated_at<now()-interval '15 minutes' THEN 1 ELSE login_attempts.failure_count+1 END,locked_until=CASE WHEN login_attempts.failure_count>=4 AND login_attempts.updated_at>=now()-interval '15 minutes' THEN now()+interval '15 minutes' ELSE NULL END,updated_at=now()",email);
    }
    void clear(String email){db.update("DELETE FROM login_attempts WHERE email=lower(?)",email);}
}
