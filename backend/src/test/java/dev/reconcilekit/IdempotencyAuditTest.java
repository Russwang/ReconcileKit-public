package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.allocation.*;
import dev.reconcilekit.transaction.TransactionObserver.Context;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TransactionTestConfiguration.class)
class IdempotencyAuditTest extends PostgreSqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AllocationService service;
    @Autowired ControlledTransactionObserver observer;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    LedgerFixture fixture;
    @BeforeEach void reset() { observer.reset(); fixture = new LedgerFixture(jdbc); }
    @AfterEach void resetObserver() { observer.reset(); }

    @Test void t07SameKeyConcurrentHttpRequestsReplayOneCommittedOperation() throws Exception { sameKeyRace(false); }
    @Test void t07RolledBackClaimLetsTheBlockedRequestTakeOver() throws Exception { sameKeyRace(true); }

    void sameKeyRace(boolean rollbackFirst) throws Exception {
        long payment = fixture.payment("P", "80"), invoice = fixture.invoice("I", "80");
        String body = body(payment, invoice, "80");
        CountDownLatch firstClaimed = new CountDownLatch(1), release = new CountDownLatch(1), secondBeforeClaim = new CountDownLatch(1);
        AtomicReference<Context> first = new AtomicReference<>(), second = new AtomicReference<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> a = null, b = null;
        observer.hook = event -> {
            if (event.stage().equals("started")) {
                assertEquals("read committed", event.isolation());
                if (!first.compareAndSet(null, event.context())) second.set(event.context());
            }
            if (event.stage().equals("claimed") && event.context().equals(first.get())) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE key='same-key' AND response_body IS NULL", Integer.class));
                firstClaimed.countDown();
                await(release, "release first real unique-key claimant");
                if (rollbackFirst) throw new IllegalStateException("synthetic claim rollback");
            }
            if (event.stage().equals("beforeClaim") && event.context().equals(second.get())) secondBeforeClaim.countDown();
        };
        try {
            a = workers.submit(() -> post(body, "same-key", "claim-first"));
            await(firstClaimed, "first transaction claimed the actual unique key");
            b = workers.submit(() -> post(body, "same-key", "claim-second"));
            await(secondBeforeClaim, "second transaction reached INSERT ON CONFLICT");
            assertNotEquals(first.get().backendPid(), second.get().backendPid());
            awaitBlockedBy(second.get().backendPid(), first.get().backendPid());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class), "Uncommitted claim is invisible to the third connection");
            release.countDown();
            var firstResponse = a.get(8, TimeUnit.SECONDS);
            var secondResponse = b.get(8, TimeUnit.SECONDS);
            assertEquals(201, secondResponse.statusCode());
            assertTrue(secondResponse.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            UUID committed = UUID.fromString(mapper.readTree(secondResponse.body()).get("operationId").asString());
            if (rollbackFirst) {
                assertEquals(500, firstResponse.statusCode());
                assertError(firstResponse, "claim-first");
                assertNotEquals(first.get().operationId(), committed);
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE operation_id=?", Integer.class, first.get().operationId()));
                assertTrue(secondResponse.headers().firstValue("Idempotency-Replayed").isEmpty());
            } else {
                assertEquals(201, firstResponse.statusCode());
                assertEquals(firstResponse.body(), secondResponse.body());
                assertEquals("true", secondResponse.headers().firstValue("Idempotency-Replayed").orElseThrow());
                assertTrue(observer.events.stream().filter(event -> event.context().equals(second.get())).noneMatch(event -> event.stage().equals("beforeLock") || event.stage().equals("balances") || event.stage().equals("inserted")));
            }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=? AND event_type='ALLOCATION_COMMITTED'", Integer.class, committed));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE key='same-key' AND operation_id=? AND response_status=201 AND response_body=?", Integer.class, committed, secondResponse.body()));
            completeInvariants();
            System.out.println("M05 unique-claim evidence: contender backend " + second.get().backendPid() + " blocked by " + first.get().backendPid() + "; " + (rollbackFirst ? "first rollback/second takeover" : "two201/exact body replay") + "; one allocation/audit/idempotency result");
        } finally {
            release.countDown();
            if (a != null && !a.isDone()) a.cancel(true);
            if (b != null && !b.isDone()) b.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void t08EquivalentRequestsReplayTheSavedSnapshotAfterPaymentIsExhausted() throws Exception {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "100"), second = fixture.invoice("I2", "100");
        String original = multiBody(payment, first, "60", second, "10");
        var saved = post(original, "semantic-key", "original-request");
        assertEquals(201, saved.statusCode());
        assertEquals("30.00", mapper.readTree(saved.body()).get("payment").get("remaining").asString());
        String persistedHash = jdbc.queryForObject("SELECT request_hash FROM idempotency_records WHERE key='semantic-key'", String.class);
        for (String amount : List.of("60", "60.0", "60.00", "60.000")) {
            observer.reset();
            String reordered = "{\"allocations\":[{\"amount\":\"10.00\",\"invoice_id\":" + second + "},{\"amount\":\"" + amount + "\",\"invoice_id\":" + first + "}],\"payment_id\":" + payment + "}";
            var replay = post(reordered, "semantic-key", "changed-correlation");
            assertEquals(201, replay.statusCode());
            assertEquals(saved.body(), replay.body());
            assertTrue(observer.events.stream().noneMatch(event -> event.stage().equals("beforeLock") || event.stage().equals("balances")));
            assertEquals(persistedHash, jdbc.queryForObject("SELECT request_hash FROM idempotency_records WHERE key='semantic-key'", String.class));
        }
        assertEquals(201, post(body(payment, first, "30"), "consume-rest", "consume-rest").statusCode());
        observer.reset();
        var exhaustedReplay = post(original, "semantic-key", "after-exhaustion");
        assertEquals(201, exhaustedReplay.statusCode());
        assertEquals(saved.body(), exhaustedReplay.body());
        assertEquals("30.00", mapper.readTree(exhaustedReplay.body()).get("payment").get("remaining").asString());
        var current = get("/api/v1/payments", "current-balance");
        assertEquals("0.00", mapper.readTree(current.body()).get("items").get(0).get("remaining").asString());
        assertTrue(observer.events.stream().noneMatch(event -> event.stage().equals("beforeLock") || event.stage().equals("balances") || event.stage().equals("inserted")));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
        completeInvariants();
    }

    @Test void t08SameKeyChangingAmountPaymentOrTargetIsConflictBeforeParentLocks() throws Exception {
        long payment = fixture.payment("P", "200"), otherPayment = fixture.payment("P2", "200");
        long invoice = fixture.invoice("I", "200"), otherInvoice = fixture.invoice("I2", "200");
        assertEquals(201, post(body(payment, invoice, "60"), "fixed-key", "saved").statusCode());
        for (String conflicting : List.of(body(payment, invoice, "61"), body(otherPayment, invoice, "60"), body(payment, otherInvoice, "60"))) {
            observer.reset();
            var response = post(conflicting, "fixed-key", "conflict");
            assertEquals(409, response.statusCode());
            assertError(response, "conflict");
            assertTrue(observer.events.stream().noneMatch(event -> event.stage().equals("beforeLock")));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        }
        completeInvariants();
    }

    @Test void semanticHashHasAGoldenStableEncodingAndIgnoresTargetOrder() {
        var unordered = AllocationTransactionsTest.command(42, AllocationTransactionsTest.target(11, "20.000"), AllocationTransactionsTest.target(10, "60"));
        assertEquals("3e9803908ce8679ee2f56a70951348373c19c0a526ddc5f6ca67692eba722062", AllocationRequestHash.of(unordered.canonicalized()));
        var reordered = AllocationTransactionsTest.command(42, AllocationTransactionsTest.target(10, "60.00"), AllocationTransactionsTest.target(11, "20"));
        assertEquals(AllocationRequestHash.of(unordered.canonicalized()), AllocationRequestHash.of(reordered.canonicalized()));
    }

    @ParameterizedTest @ValueSource(strings = {"inserted", "beforeCommit"})
    void t09ActualSqlFaultRollsBackAllocationsAuditClaimAndSavedResponse(String faultStage) throws Exception {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "100"), second = fixture.invoice("I2", "100");
        String body = multiBody(payment, first, "30", second, "20");
        AtomicReference<UUID> operation = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals(faultStage) && (!faultStage.equals("inserted") || event.insertedCount() == 1)) {
                operation.set(event.context().operationId());
                assertEquals(faultStage.equals("inserted") ? 1 : 2, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE operation_id=?", Integer.class, operation.get()));
                assertEquals(faultStage.equals("inserted") ? 0 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=?", Integer.class, operation.get()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE key='fault-key' AND operation_id=?", Integer.class, operation.get()));
                if (faultStage.equals("beforeCommit")) {
                    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE key='fault-key' AND response_status=201 AND response_body IS NOT NULL", Integer.class));
                    String audit = jdbc.queryForObject("SELECT detail FROM audit_events WHERE operation_id=?", String.class, operation.get());
                    assertEquals(2, mapper.readTree(audit).get("allocations").size());
                }
                throw new IllegalStateException("synthetic actual-SQL fault");
            }
        };
        var failure = post(body, "fault-key", "fault-request");
        assertEquals(500, failure.statusCode());
        assertError(failure, "fault-request");
        assertNotNull(operation.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        observer.reset();
        var retry = post(body, "fault-key", "fault-cleared");
        assertEquals(201, retry.statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        completeInvariants();
    }

    @Test void t09SecondTargetInsufficiencyLeavesNoClaimAuditOrPartialWrite() throws Exception {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "100"), second = fixture.invoice("I2", "10");
        var rejected = post(multiBody(payment, first, "20", second, "20"), "reusable-failure-key", "insufficient");
        assertEquals(409, rejected.statusCode());
        assertNoAllocationFacts();
        assertEquals(201, post(body(payment, first, "10"), "reusable-failure-key", "new-content-after-failure").statusCode());
        completeInvariants();
    }

    @Test void t15HttpLockTimeoutReturns503RollsBackClaimAndSameKeyRetryCommitsOnce() throws Exception {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        String body = body(payment, invoice, "40");
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Future<HttpResponse<String>> pending = null;
        try (Connection fault = dataSource.getConnection()) {
            fault.setAutoCommit(false);
            int holder;
            try (var statement = fault.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) { assertTrue(rows.next()); holder = rows.getInt(1); }
            try (var statement = fault.prepareStatement("SELECT id FROM payments WHERE id=? FOR UPDATE")) { statement.setLong(1, payment); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); } }
            CountDownLatch beforeLock = new CountDownLatch(1);
            AtomicReference<Context> context = new AtomicReference<>();
            observer.hook = event -> {
                if (event.stage().equals("started")) context.set(event.context());
                if (event.stage().equals("beforeLock")) beforeLock.countDown();
            };
            pending = workers.submit(() -> post(body, "timeout-key", "real-timeout"));
            await(beforeLock, "HTTP transaction claimed key and reached blocked parent lock");
            awaitBlockedBy(context.get().backendPid(), holder);
            var failure = pending.get(6, TimeUnit.SECONDS);
            assertEquals(503, failure.statusCode());
            assertError(failure, "real-timeout");
            assertEquals("RETRYABLE_DATABASE_ERROR", mapper.readTree(failure.body()).get("code").asString());
            assertNoAllocationFacts();
            fault.rollback();
            observer.reset();
            var retry = post(body, "timeout-key", "lock-released");
            assertEquals(201, retry.statusCode());
            var replay = post(body, "timeout-key", "timeout-retry-again");
            assertEquals(retry.body(), replay.body());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            completeInvariants();
            System.out.println("M05 HTTP timeout evidence: backend " + context.get().backendPid() + " blocked by " + holder + "; HTTP503/no facts; same-key retry201 then exact replay");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void terminatedManualBackendReturns503RollsBackAllFactsAndSameKeyRetryReplays() throws Exception {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "100"), second = fixture.invoice("I2", "100");
        String body = multiBody(payment, first, "30", second, "20");
        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Context> context = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals("inserted") && event.insertedCount() == 1) {
                context.set(event.context());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE operation_id=?", Integer.class, event.context().operationId()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_id=? AND response_body IS NULL", Integer.class, event.context().operationId()));
                inserted.countDown(); await(release, "resume manual allocation after real backend termination");
            }
        };
        ExecutorService workers = Executors.newSingleThreadExecutor(); Future<HttpResponse<String>> pending = null;
        try {
            pending = workers.submit(() -> post(body, "terminated-manual-key", "terminated-manual-request"));
            await(inserted, "manual allocation actually INSERTed its first row");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
            assertTrue(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, context.get().backendPid()));
            release.countDown();
            var response = pending.get(8, TimeUnit.SECONDS);
            assertEquals(503, response.statusCode(), response.body()); assertError(response, "terminated-manual-request");
            assertEquals("RETRYABLE_DATABASE_ERROR", mapper.readTree(response.body()).get("code").asString());
            assertNoAllocationFacts();
            observer.reset();
            var retry = post(body, "terminated-manual-key", "manual-connection-recovered");
            assertEquals(201, retry.statusCode());
            var replay = post(body, "terminated-manual-key", "manual-retry-again");
            assertEquals(201, replay.statusCode()); assertEquals(retry.body(), replay.body());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
            completeInvariants();
            System.out.println("M06 manual backend termination evidence: pg_terminate_backend(" + context.get().backendPid() + ") after actual INSERT/claim; HTTP503/all facts rollback/same-key retry201/exact replay");
        } finally {
            release.countDown(); if (pending != null && !pending.isDone()) pending.cancel(true);
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void auditQueryReturnsActualAllocationDetailsStablePagesAndIsReadOnly() throws Exception {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        post(body(payment, invoice, "30"), "audit-1", "audit-1");
        var second = post(body(payment, invoice, "20"), "audit-2", "audit-2");
        var result = get("/api/v1/audit-events?page=1&size=1", "audit-page");
        assertEquals(200, result.statusCode());
        var rows = mapper.readTree(result.body()).get("items");
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals(2L, row.get("id").asLong());
        assertEquals(mapper.readTree(second.body()).get("operationId").asString(), row.get("operationId").asString());
        assertEquals("local", row.get("actor").asString());
        assertEquals("ALLOCATION_COMMITTED", row.get("eventType").asString());
        var detail = row.get("detail").get("allocations").get(0);
        assertEquals(mapper.readTree(second.body()).get("allocationIds").get(0).asLong(), detail.get("allocationId").asLong());
        assertEquals(payment, detail.get("paymentId").asLong());
        assertEquals(invoice, detail.get("invoiceId").asLong());
        assertEquals("20.00", detail.get("amount").asString());
        assertEquals("MANUAL", detail.get("origin").asString());
        assertEquals(400, get("/api/v1/audit-events?size=101", "audit-invalid").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        completeInvariants();
    }

    @Test void idempotencyHeaderIsRequiredTrimmedBoundedAndCaseSensitive() throws Exception {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        for (String invalid : Arrays.asList(null, "", "   ", "x".repeat(201))) {
            assertEquals(400, post(body(payment, invoice, "10"), invalid, "bad-key").statusCode());
            assertNoAllocationFacts();
        }
        var original = service.allocate(AllocationTransactionsTest.command(payment, AllocationTransactionsTest.target(invoice, "10")), "  TrimKey  ");
        var replay = post(body(payment, invoice, "10.000"), "TrimKey", "trimmed");
        assertEquals(201, replay.statusCode());
        assertEquals(original.body(), replay.body());
        assertEquals(201, post(body(payment, invoice, "10"), "trimkey", "case-distinct").statusCode());
        assertEquals(201, post(body(payment, invoice, "10"), "x".repeat(200), "upper-bound-key").statusCode());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        completeInvariants();
    }

    @Test void missingParentsRollbackTheirClaimAndUnknownRoutesMethodsMediaUseCorrectStatuses() throws Exception {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        assertEquals(404, post(body(payment, 9999, "10"), "missing-invoice", "missing-invoice").statusCode());
        assertEquals(404, post(body(9999, invoice, "10"), "missing-payment", "missing-payment").statusCode());
        assertEquals(404, get("/api/v1/not-an-endpoint", "unknown").statusCode());
        var wrongMethod = get("/api/v1/allocations", "wrong-method");
        assertEquals(405, wrongMethod.statusCode());
        assertTrue(wrongMethod.headers().firstValue("Allow").orElseThrow().contains("POST"));
        var media = client.send(request("/api/v1/allocations", "wrong-media").header("Content-Type", "text/plain").header("Idempotency-Key", "media")
                .POST(HttpRequest.BodyPublishers.ofString(body(payment, invoice, "10"))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, media.statusCode());
        assertError(media, "wrong-media");
        assertNoAllocationFacts();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "0.001", "1000000000000.00", "NaN", "Infinity", "1e2", "£1", ""})
    void allocationHttpRejectsInvalidMonetaryStringsWithoutAnyFacts(String amount) throws Exception {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        var response = post(body(payment, invoice, amount), "invalid-amount", "invalid-amount");
        assertEquals(422, response.statusCode());
        assertError(response, "invalid-amount");
        assertNoAllocationFacts();
    }

    static Stream<Arguments> malformedAllocations() {
        return Stream.of(
                Arguments.of("number amount", "{\"payment_id\":1,\"allocations\":[{\"invoice_id\":1,\"amount\":60}]}"),
                Arguments.of("null amount", "{\"payment_id\":1,\"allocations\":[{\"invoice_id\":1,\"amount\":null}]}"),
                Arguments.of("missing amount", "{\"payment_id\":1,\"allocations\":[{\"invoice_id\":1}]}"),
                Arguments.of("empty targets", "{\"payment_id\":1,\"allocations\":[]}"),
                Arguments.of("duplicate targets", "{\"payment_id\":1,\"allocations\":[{\"invoice_id\":1,\"amount\":\"1\"},{\"invoice_id\":1,\"amount\":\"2\"}]}"),
                Arguments.of("null target", "{\"payment_id\":1,\"allocations\":[null]}"),
                Arguments.of("wrong id type", "{\"payment_id\":\"1\",\"allocations\":[{\"invoice_id\":1,\"amount\":\"1\"}]}"),
                Arguments.of("invalid id", "{\"payment_id\":0,\"allocations\":[{\"invoice_id\":1,\"amount\":\"1\"}]}"),
                Arguments.of("client operation type", "{\"payment_id\":1,\"operation_type\":\"AUTO_ALLOCATION\",\"allocations\":[{\"invoice_id\":1,\"amount\":\"1\"}]}"),
                Arguments.of("malformed JSON", "{invalid-json"),
                Arguments.of("too many targets", "{\"payment_id\":1,\"allocations\":[" + String.join(",", java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(n -> "{\"invoice_id\":" + n + ",\"amount\":\"1\"}").toList()) + "]}"));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("malformedAllocations")
    void malformedAllocationHttpRequestsAre400BeforeClaims(String reason, String body) throws Exception {
        fixture.payment("P", "100"); fixture.invoice("I", "100");
        var response = post(body, "structure-error", "structure-error");
        assertEquals(400, response.statusCode(), reason);
        assertError(response, "structure-error");
        assertTrue(observer.events.isEmpty());
        assertNoAllocationFacts();
    }

    @Test void allocationBodyLimitCoversKnownLengthAndChunkedRequests() throws Exception {
        fixture.payment("P", "100"); fixture.invoice("I", "100");
        String large = " ".repeat(dev.reconcilekit.api.RequestGuard.BODY_LIMIT + 1);
        for (HttpRequest.BodyPublisher publisher : List.of(HttpRequest.BodyPublishers.ofString(large), HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(large.getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
            var response = client.send(request("/api/v1/allocations", "large-body").header("Content-Type", "application/json").header("Idempotency-Key", "large-body")
                    .POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode());
            assertError(response, "large-body");
            assertNoAllocationFacts();
        }
    }

    @Test void exactSmallestAndUpperAmountsReachHttpAndRemainExact() throws Exception {
        long minPayment = fixture.payment("MIN-P", "0.01"), minInvoice = fixture.invoice("MIN-I", "0.01");
        long maxPayment = fixture.payment("MAX-P", "999999999999.99"), maxInvoice = fixture.invoice("MAX-I", "999999999999.99");
        assertEquals(201, post(body(minPayment, minInvoice, "0.010"), "min-key", "min").statusCode());
        assertEquals(201, post(body(maxPayment, maxInvoice, "999999999999.99"), "max-key", "max").statusCode());
        assertEquals(new BigDecimal("0.01"), jdbc.queryForObject("SELECT amount FROM allocations WHERE payment_id=?", BigDecimal.class, minPayment));
        assertEquals(new BigDecimal("999999999999.99"), jdbc.queryForObject("SELECT amount FROM allocations WHERE payment_id=?", BigDecimal.class, maxPayment));
        completeInvariants();
    }

    HttpRequest.Builder request(String path, String requestId) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).header("X-Request-ID", requestId);
    }
    HttpResponse<String> post(String body, String key, String requestId) throws Exception {
        var request = request("/api/v1/allocations", requestId).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path, String requestId) throws Exception {
        return client.send(request(path, requestId).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static String body(long payment, long invoice, String amount) {
        return "{\"payment_id\":" + payment + ",\"allocations\":[{\"invoice_id\":" + invoice + ",\"amount\":\"" + amount + "\"}]}";
    }
    static String multiBody(long payment, long first, String firstAmount, long second, String secondAmount) {
        return "{\"payment_id\":" + payment + ",\"allocations\":[{\"invoice_id\":" + first + ",\"amount\":\"" + firstAmount + "\"},{\"invoice_id\":" + second + ",\"amount\":\"" + secondAmount + "\"}]}";
    }
    void assertError(HttpResponse<String> response, String requestId) throws Exception {
        var body = mapper.readTree(response.body());
        assertTrue(body.has("code")); assertTrue(body.has("message"));
        assertEquals(requestId, body.get("request_id").asString());
        assertEquals(requestId, response.headers().firstValue("X-Request-ID").orElseThrow());
        assertFalse(response.body().contains("SELECT")); assertFalse(response.body().contains("org.postgresql"));
        assertFalse(response.body().contains("synthetic"));
    }
    void assertNoAllocationFacts() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        completeInvariants();
    }
    void completeInvariants() {
        fixture.invariants();
        int operations = jdbc.queryForObject("SELECT COUNT(DISTINCT operation_id) FROM allocations", Integer.class);
        assertEquals(operations, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_type='ALLOCATION_COMMITTED'", Integer.class));
        assertEquals(operations, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_type='MANUAL_ALLOCATION'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE response_status IS NULL OR response_body IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records r WHERE NOT EXISTS(SELECT 1 FROM allocations a WHERE a.operation_id=r.operation_id)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events e WHERE NOT EXISTS(SELECT 1 FROM allocations a WHERE a.operation_id=e.operation_id)", Integer.class));
    }
    void awaitBlockedBy(int blocked, int blocker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        do {
            if (jdbc.queryForList("SELECT unnest(pg_blocking_pids(?))", Integer.class, blocked).contains(blocker)) {
                assertEquals("Lock", jdbc.queryForObject("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?", String.class, blocked));
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("No actual PostgreSQL blocker relationship was observed");
    }
    static void await(CountDownLatch latch, String reason) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS), reason); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(reason, error); }
    }
}
