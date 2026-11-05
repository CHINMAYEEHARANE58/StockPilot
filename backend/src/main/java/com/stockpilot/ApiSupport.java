package com.stockpilot;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiSupport {
    private final FailedStockAlertService failures;
    ApiSupport(FailedStockAlertService failures){this.failures=failures;}
    static class Conflict extends RuntimeException { Conflict(String message) { super(message); } }
    static class StockConflict extends Conflict { StockConflict(String message){super(message);} }
    static class Invalid extends RuntimeException { Invalid(String message) { super(message); } }
    static class Unauthorized extends RuntimeException { Unauthorized(String message){super(message);} }
    static class Missing extends RuntimeException { Missing(String message) { super(message); } }
    @ExceptionHandler(Conflict.class) ResponseEntity<?> conflict(Conflict ex) { return error(HttpStatus.CONFLICT,ex.getMessage()); }
    @ExceptionHandler(StockConflict.class) ResponseEntity<?> stockConflict(StockConflict ex) { failures.record(ex.getMessage());return error(HttpStatus.CONFLICT,ex.getMessage()); }
    @ExceptionHandler({Invalid.class, MethodArgumentNotValidException.class, IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception ex) { return error(HttpStatus.BAD_REQUEST,ex.getMessage()); }
    @ExceptionHandler(Missing.class) ResponseEntity<?> missing(Missing ex) { return error(HttpStatus.NOT_FOUND,ex.getMessage()); }
    @ExceptionHandler(Unauthorized.class) ResponseEntity<?> unauthorized(Unauthorized ex) { return error(HttpStatus.UNAUTHORIZED,ex.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> integrity(DataIntegrityViolationException ex) { return error(HttpStatus.CONFLICT,"Conflicting or referenced data"); }
    private ResponseEntity<?> error(HttpStatus status,String message) { return ResponseEntity.status(status).body(Map.of("error",message)); }
}
