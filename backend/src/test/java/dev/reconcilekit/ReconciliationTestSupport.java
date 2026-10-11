package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.allocation.AllocationService;
import dev.reconcilekit.reconciliation.ReconciliationRunService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

abstract class ReconciliationTestSupport extends PostgreSqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AllocationService allocations;
    @Autowired ReconciliationRunService runs;
    @Autowired ControlledTransactionObserver observer;
    @Autowired ReconciliationTestConfiguration.ControlledRunObserver runObserver;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    LedgerFixture fixture;
    @BeforeEach void reset() { observer.reset(); runObserver.reset(); fixture = new LedgerFixture(jdbc); }
    @AfterEach void resetHooks() { observer.reset(); runObserver.reset(); }
    long targeted(String name, String amount, String source, String id) {
        long payment = fixture.payment(name, amount);
        jdbc.update("UPDATE payments SET target_invoice_source=?,target_external_invoice_id=? WHERE id=?", source, id, payment);
        return payment;
    }
    HttpResponse<String> post(String path, String body) throws Exception { return post(path, body, null); }
    HttpResponse<String> post(String path, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Request-ID", "m08-evidence");
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body());
    }
    JsonNode run(long... ids) throws Exception {
        var response = post("/api/v1/reconciliation/runs", mapper.writeValueAsString(Map.of("payment_ids", ids)));
        assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body());
    }
    JsonNode item(JsonNode report, long id) {
        for (var item : report.get("items")) if (item.get("paymentId").asLong() == id) return item;
        fail("Missing payment result " + id); return null;
    }
    void counts(JsonNode report, int applied, int skipped, int review, int failed) {
        assertEquals(applied + skipped + review + failed, report.get("total").asInt());
        assertEquals(report.get("total").asInt(), report.get("items").size());
        assertEquals(applied, report.get("applied").asInt()); assertEquals(skipped, report.get("skipped").asInt());
        assertEquals(review, report.get("review").asInt()); assertEquals(failed, report.get("failed").asInt());
        assertNotNull(UUID.fromString(report.get("runId").asString()));
    }
    void reason(JsonNode report, long payment, String expected) {
        var result = item(report, payment); assertEquals("REVIEW", result.get("status").asString());
        assertEquals(expected, result.get("reason").asString()); assertFalse(result.get("replayed").asBoolean());
        assertTrue(result.get("operationId").isNull()); assertEquals(0, result.get("allocationIds").size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_type='AUTO_ALLOCATION' AND key=?", Integer.class, "auto:v1:" + payment));
    }
    Map<Long, JsonNode> pending() throws Exception {
        var result = new LinkedHashMap<Long, JsonNode>();
        for (var row : get("/api/v1/reconciliation?size=100").get("items")) result.put(row.get("id").asLong(), row);
        return result;
    }
    BigDecimal sum(String foreign, long id) { return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0)::numeric(19,2) FROM allocations WHERE " + foreign + "=?", BigDecimal.class, id); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    void facts(int committedOperations) {
        fixture.invariants();
        assertEquals(committedOperations, count("idempotency_records"));
        assertEquals(committedOperations, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_type='ALLOCATION_COMMITTED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE response_status IS NULL OR response_body IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations a LEFT JOIN idempotency_records i ON i.operation_id=a.operation_id LEFT JOIN audit_events e ON e.operation_id=a.operation_id AND e.event_type='ALLOCATION_COMMITTED' WHERE i.operation_id IS NULL OR e.operation_id IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations a JOIN idempotency_records i ON i.operation_id=a.operation_id JOIN audit_events e ON e.operation_id=a.operation_id WHERE (a.origin='AUTO' AND (i.operation_type<>'AUTO_ALLOCATION' OR e.actor<>'system')) OR (a.origin='MANUAL' AND (i.operation_type<>'MANUAL_ALLOCATION' OR e.actor<>'local'))", Integer.class));
    }
}
