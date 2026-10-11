package dev.reconcilekit.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpServletRequest;

/** Exercise the production advice with an actual captured database failure, without a fault route. */
public final class DatabaseErrorAssertions {
    private DatabaseErrorAssertions() {}
    public static void retryable(DataAccessException actual) {
        var request = new MockHttpServletRequest();
        request.setAttribute("request_id", "m04-real-lock-timeout");
        var response = new Errors().database(actual, request);
        assertEquals(503, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("RETRYABLE_DATABASE_ERROR", body.get("code"));
        assertEquals("m04-real-lock-timeout", body.get("request_id"));
        assertEquals("Database temporarily unavailable; retry with the same key and content", body.get("message"));
        assertFalse(body.toString().contains("SELECT"));
    }
}
