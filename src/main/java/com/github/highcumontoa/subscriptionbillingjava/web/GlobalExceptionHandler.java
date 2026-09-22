package com.github.highcumontoa.subscriptionbillingjava.web;

import com.github.highcumontoa.subscriptionbillingjava.error.BillingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, Object>> handle(BillingException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "errorCode", ex.getErrorCode().name(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
