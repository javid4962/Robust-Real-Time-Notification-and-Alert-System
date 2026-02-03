package com.datavalley.notification.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Collections;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.datavalley.notification.exception.UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(com.datavalley.notification.exception.UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Collections.singletonMap("error", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        // Assume other RuntimeExceptions are Rate Limits or unexpected errors
        // Ideally, create a specific RateLimitException too.
        if (ex.getMessage().contains("Rate limit")) {
             return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Collections.singletonMap("error", ex.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Collections.singletonMap("error", ex.getMessage()));
    }
}
