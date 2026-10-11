package dev.reconcilekit.importbatch;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportErrors {
    @ExceptionHandler(ImportFailure.class)
    ResponseEntity<?> failure(ImportFailure error, HttpServletRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", error.code()); body.put("message", error.getMessage());
        body.put("request_id", String.valueOf(request.getAttribute("request_id")));
        body.put("attemptId", error.report().attemptId());
        body.put("rowErrors", error.report().rowErrors());
        body.put("report", error.report()); body.put("attemptSaved", error.attemptSaved());
        body.put("outcomeUnknown", error.outcomeUnknown());
        return ResponseEntity.status(error.status()).body(body);
    }
}
