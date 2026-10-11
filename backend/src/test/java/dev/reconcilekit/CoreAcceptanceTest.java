package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The frozen core answer is checked via production HTTP, plus independent database sums. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreAcceptanceTest extends PostgreSqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void emptySyntheticLedger() {
        jdbc.execute("TRUNCATE audit_events,idempotency_records,allocations,payments,invoices,import_batches RESTART IDENTITY");
    }

    @Test void t01FrozenJsonDemoImportsAllocatesReplaysAndMatchesEveryIndependentState() throws Exception {
        for (String kind : List.of("invoices", "payments")) {
            String payload = Files.readString(Path.of("..", "examples", kind + ".json"));
            int total = kind.equals("invoices") ? 3 : 2;
            var imported = json(post("/imports/" + kind, payload, null, 201));
            assertEquals(total, imported.get("inserted").asInt());
            assertEquals(0, imported.get("duplicate").asInt());
            assertEquals(imported, json(get("/import-batches/" + imported.get("attemptId").asString())));
            var repeated = json(post("/imports/" + kind, payload, null, 201));
            assertEquals(0, repeated.get("inserted").asInt());
            assertEquals(total, repeated.get("duplicate").asInt());
            assertEquals(0, repeated.get("notImported").asInt());
        }
        assertEquals(0, count("allocations"));
        var invoices = json(get("/invoices")).get("items");
        var payments = json(get("/payments")).get("items");
        var invoiceIds = identities(invoices, "externalInvoiceId");
        var paymentIds = identities(payments, "externalPaymentId");
        assertEquals(2, json(get("/reconciliation")).get("items").size());
        var firstCommand = mapper.createObjectNode().put("payment_id", paymentIds.get("PAY-001"));
        var targets = firstCommand.putArray("allocations");
        targets.addObject().put("invoice_id", invoiceIds.get("INV-102")).put("amount", "80.00");
        targets.addObject().put("invoice_id", invoiceIds.get("INV-101")).put("amount", "120.00");
        var first = post("/allocations", mapper.writeValueAsString(firstCommand), "core-test:manual:1", 201);
        var replay = post("/allocations", mapper.writeValueAsString(firstCommand), "core-test:manual:1", 201);
        assertArrayEquals(first.body(), replay.body());
        assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        var pending = json(get("/reconciliation")).get("items");
        assertEquals(1, pending.size());
        assertEquals("PAY-002", pending.get(0).get("externalPaymentId").asString());
        assertEquals("UNALLOCATED", pending.get(0).get("status").asString());
        String secondCommand = "{\"payment_id\":" + paymentIds.get("PAY-002") +
                ",\"allocations\":[{\"invoice_id\":" + invoiceIds.get("INV-103") + ",\"amount\":\"60\"}]}";
        var second = post("/allocations", secondCommand, "core-test:manual:2", 201);
        assertArrayEquals(second.body(), post("/allocations", secondCommand, "core-test:manual:2", 201).body());
        String excess = secondCommand.replace("\"60\"", "\"0.01\"");
        post("/allocations", excess, "core-test:manual:2", 409);
        post("/allocations", excess, "core-test:extra", 409);
        var expected = mapper.readTree(Files.readString(Path.of("..", "examples", "expected-core.json")));
        var actual = mapper.createObjectNode();
        for (String kind : List.of("invoices", "payments")) {
            String identity = kind.equals("invoices") ? "externalInvoiceId" : "externalPaymentId";
            var output = actual.putArray(kind);
            for (var item : json(get("/" + kind)).get("items"))
                output.addObject().put(identity, item.get(identity).asString())
                        .put("status", item.get("status").asString()).put("remaining", item.get("remaining").asString());
        }
        assertEquals(expected, actual);
        assertEquals(0, json(get("/reconciliation")).get("items").size());
        assertEquals(3, count("invoices")); assertEquals(2, count("payments"));
        assertEquals(3, count("allocations")); assertEquals(6, count("audit_events"));
        assertEquals(2, count("idempotency_records")); assertEquals(4, count("import_batches"));
        var audits = json(get("/audit-events")).get("items");
        for (var response : List.of(first, second)) {
            String operation = json(response).get("operationId").asString();
            assertEquals(1, java.util.stream.StreamSupport.stream(audits.spliterator(), false)
                    .filter(row -> row.get("operationId").asString().equals(operation)).count());
        }
        for (String parent : List.of("payments", "invoices")) {
            String foreign = parent.equals("payments") ? "payment_id" : "invoice_id";
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT p.id FROM " + parent +
                    " p LEFT JOIN allocations a ON a." + foreign + "=p.id GROUP BY p.id,p.amount" +
                    " HAVING COALESCE(SUM(a.amount),0)<0 OR COALESCE(SUM(a.amount),0)>p.amount) bad", Integer.class));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE response_body IS NULL OR response_status IS NULL", Integer.class));
        System.out.println("M07 T01 golden answer: three invoices PAID/PAID/PARTIALLY_PAID40; both payments FULLY_ALLOCATED0; pending0; exact replay; independent SQL bounds PASS");
    }

    @Test void t07CallerDiscardsFirstSuccessfulBodyThenRetriesStoredResultWithoutEffects() throws Exception {
        post("/imports/invoices", """
                {"source":"demo-invoices","rows":[{"source":"demo-invoices",
                "external_invoice_id":"DISCARD-INVOICE","amount":"60","currency":"GBP","issued_on":"2026-10-01"}]}
                """, null, 201);
        post("/imports/payments", """
                {"source":"demo-payments","rows":[{"source":"demo-payments",
                "external_payment_id":"DISCARD-PAYMENT","amount":"60","currency":"GBP","received_on":"2026-10-02"}]}
                """, null, 201);
        long invoice = jdbc.queryForObject("SELECT id FROM invoices WHERE external_invoice_id='DISCARD-INVOICE'", Long.class);
        long payment = jdbc.queryForObject("SELECT id FROM payments WHERE external_payment_id='DISCARD-PAYMENT'", Long.class);
        String command = "{\"payment_id\":" + payment + ",\"allocations\":[{\"invoice_id\":" + invoice + ",\"amount\":\"60\"}]}";
        String key = "core-test:discarded-body";
        var discarded = client.send(request("/allocations").header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(command)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(201, discarded.statusCode());
        assertNull(discarded.body());
        String saved = jdbc.queryForObject("SELECT response_body FROM idempotency_records WHERE operation_type='MANUAL_ALLOCATION' AND key=?", String.class, key);
        assertEquals(201, jdbc.queryForObject("SELECT response_status FROM idempotency_records WHERE key=?", Integer.class, key));
        assertEquals(1, count("allocations")); assertEquals(1, count("idempotency_records"));
        assertEquals(3, count("audit_events"));
        var replay = post("/allocations", command, key, 201);
        assertArrayEquals(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8), replay.body());
        assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals(1, count("allocations")); assertEquals(1, count("idempotency_records"));
        assertEquals(3, count("audit_events"));
        assertEquals(0, json(get("/reconciliation")).get("items").size());
        System.out.println("M07 T07 caller discarded first successful HTTP body; retry matches independently read stored201/body, unchanged allocation/audit/key counts; no claim of transport packet loss");
    }

    Map<String,Long> identities(JsonNode rows, String field) {
        var result = new HashMap<String,Long>();
        for (var row : rows) result.put(row.get(field).asString(), row.get("id").asLong());
        return result;
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    JsonNode json(HttpResponse<byte[]> response) { return mapper.readTree(response.body()); }
    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10)).header("X-Request-ID", "core-test");
    }
    HttpResponse<byte[]> get(String path) throws Exception {
        var response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode()); return response;
    }
    HttpResponse<byte[]> post(String path, String body, String key, int expected) throws Exception {
        var request = request(path).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected, response.statusCode(), () -> new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        return response;
    }
}
