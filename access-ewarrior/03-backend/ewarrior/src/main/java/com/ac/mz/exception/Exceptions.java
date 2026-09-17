package com.ac.mz.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The exception types services throw, plus the single advice that turns them into
 * the documented error body. Controllers never write try/catch for these.
 */
public final class Exceptions {

    private Exceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String m) { super(m); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String m) { super(m); }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String m) { super(m); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String m) { super(m); }
    }

    @RestControllerAdvice
    public static class GlobalExceptionHandler {

        private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

        private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, HttpServletRequest req) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", Instant.now().toString());
            m.put("status", status.value());
            m.put("error", status.getReasonPhrase());
            m.put("message", message);
            m.put("path", req.getRequestURI());
            return ResponseEntity.status(status).body(m);
        }

        @ExceptionHandler(NotFoundException.class)
        public ResponseEntity<Map<String, Object>> notFound(NotFoundException e, HttpServletRequest r) {
            return body(HttpStatus.NOT_FOUND, e.getMessage(), r);
        }

        @ExceptionHandler(ForbiddenException.class)
        public ResponseEntity<Map<String, Object>> forbidden(ForbiddenException e, HttpServletRequest r) {
            return body(HttpStatus.FORBIDDEN, e.getMessage(), r);
        }

        @ExceptionHandler(ConflictException.class)
        public ResponseEntity<Map<String, Object>> conflict(ConflictException e, HttpServletRequest r) {
            return body(HttpStatus.CONFLICT, e.getMessage(), r);
        }

        @ExceptionHandler(UnauthorizedException.class)
        public ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedException e, HttpServletRequest r) {
            return body(HttpStatus.UNAUTHORIZED, e.getMessage(), r);
        }

        @ExceptionHandler(BadRequestException.class)
        public ResponseEntity<Map<String, Object>> badRequest(BadRequestException e, HttpServletRequest r) {
            return body(HttpStatus.BAD_REQUEST, e.getMessage(), r);
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e, HttpServletRequest r) {
            String msg = e.getBindingResult().getFieldErrors().stream().findFirst()
                    .map(f -> f.getField() + " " + f.getDefaultMessage())
                    .orElse("Validation failed");
            return body(HttpStatus.BAD_REQUEST, msg, r);
        }

        /** Never leak a stack trace or an internal message to the client. */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest r) {
            log.error("Unhandled exception on {}", r.getRequestURI(), e);
            return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", r);
        }
    }
}
