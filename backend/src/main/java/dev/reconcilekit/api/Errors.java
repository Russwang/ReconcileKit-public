package dev.reconcilekit.api;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception error, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Malformed JSON, header or parameter", request);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound(Exception error, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> methodNotAllowed(HttpRequestMethodNotSupportedException error, HttpServletRequest request) {
        String[] supported = error.getSupportedMethods();
        return ResponseEntity.status(405).header("Allow", supported == null ? "" : String.join(", ", supported))
                .body(Map.of("code", "METHOD_NOT_ALLOWED", "message", "Method not allowed", "request_id", String.valueOf(request.getAttribute("request_id"))));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> unsupportedMedia(Exception error, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json", request);
    }
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<?> unacceptableMedia(Exception error, HttpServletRequest request) {
        return response(406, "NOT_ACCEPTABLE", "Response content type is application/json", request);
    }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    ResponseEntity<?> database(Exception error, HttpServletRequest request) {
        boolean retryable = DatabaseFailures.retryable(error);
        return response(retryable ? 503 : 500, retryable ? "RETRYABLE_DATABASE_ERROR" : "INTERNAL_ERROR",
                retryable ? "Database temporarily unavailable; retry with the same key and content" : "Internal operation failed", request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception error, HttpServletRequest request) {
        LoggerFactory.getLogger(Errors.class).warn("Internal request failure: request_id={}, type={}", request.getAttribute("request_id"), error.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "Internal operation failed", request);
    }
    private ResponseEntity<?> response(int status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "request_id", String.valueOf(request.getAttribute("request_id"))));
    }
}
