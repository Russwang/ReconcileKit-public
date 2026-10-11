package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.transaction.TransactionObserver.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/** A03/A05 prove actual unique/parent-lock waits and independently committed PostgreSQL sums. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TransactionTestConfiguration.class, ReconciliationTestConfiguration.class})
class ReconciliationTransactionsTest extends ReconciliationTestSupport {
    record Response(int status, JsonNode body) {}
    Response automatic(long payment) throws Exception { return new Response(200, run(payment)); }
    Response manual(long payment, long invoice, String amount) throws Exception {
        var response = post("/api/v1/allocations", "{\"payment_id\":" + payment + ",\"allocations\":[{\"invoice_id\":" + invoice + ",\"amount\":\"" + amount + "\"}]}", "race-manual:" + payment);
        return new Response(response.statusCode(), mapper.readTree(response.body()));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void a03ConcurrentAutomaticStableClaimReallyBlocksAndReplaysOrTakesOverAfterRollback(boolean rollbackFirst) throws Exception {
        fixture.invoice("I", "100"); long payment = targeted("P", "60", "demo-invoices", "I");
        CountDownLatch claimed = new CountDownLatch(1), release = new CountDownLatch(1), beforeSecond = new CountDownLatch(1);
        AtomicReference<Context> first = new AtomicReference<>(), second = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals("started")) { assertEquals("read committed", event.isolation()); if (!first.compareAndSet(null, event.context())) second.set(event.context()); }
            if (event.stage().equals("claimed") && event.context().equals(first.get())) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_type='AUTO_ALLOCATION' AND key=? AND response_body IS NULL", Integer.class, "auto:v1:" + payment));
                claimed.countDown(); await(release, "release true automatic unique claimant");
                if (rollbackFirst) throw new IllegalStateException("synthetic claimant rollback");
            }
            if (event.stage().equals("beforeClaim") && event.context().equals(second.get())) beforeSecond.countDown();
        };
        ExecutorService workers = Executors.newFixedThreadPool(2); Future<JsonNode> a = null, b = null;
        try {
            a = workers.submit(() -> run(payment)); await(claimed, "first actual AUTO key INSERT");
            b = workers.submit(() -> run(payment)); await(beforeSecond, "second reached same AUTO INSERT ON CONFLICT");
            awaitBlockedBy(second.get().backendPid(), first.get().backendPid()); assertEquals(0, count("idempotency_records"));
            release.countDown(); var ar = a.get(8, TimeUnit.SECONDS); var br = b.get(8, TimeUnit.SECONDS);
            counts(ar, rollbackFirst ? 0 : 1, 0, 0, rollbackFirst ? 1 : 0); counts(br, rollbackFirst ? 1 : 0, rollbackFirst ? 0 : 1, 0, 0);
            assertEquals(!rollbackFirst, item(br, payment).get("replayed").asBoolean());
            if (!rollbackFirst) {
                assertEquals(item(ar, payment).get("operationId"), item(br, payment).get("operationId"));
                assertTrue(events(second.get()).stream().noneMatch(e -> e.stage().equals("beforeLock") || e.stage().equals("inserted") || e.stage().equals("balances")));
            } else assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE operation_id=?", Integer.class, first.get().operationId()));
            assertEquals(1, count("allocations")); assertEquals(new BigDecimal("60.00"), sum("payment_id", payment)); facts(1);
            System.out.println("M08 AUTO unique evidence: backend " + second.get().backendPid() + " blocked by " + first.get().backendPid() + "; " + (rollbackFirst ? "rollback/takeover" : "one commit/original replay"));
        } finally { release.countDown(); cleanup(workers, a, b); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void a05ManualAndAutomaticSharePaymentLockAndRecheckManualHistoryInBothOrders(boolean autoFirst) throws Exception {
        long invoice = fixture.invoice("I", "100"), payment = targeted("P", "60", "demo-invoices", "I");
        var race = compete(autoFirst ? () -> automatic(payment) : () -> manual(payment, invoice, "10"),
                autoFirst ? () -> manual(payment, invoice, "10") : () -> automatic(payment), Parent.PAYMENT, payment);
        if (autoFirst) { counts(race.first().body(), 1, 0, 0, 0); assertEquals(409, race.second().status()); assertEquals(new BigDecimal("60.00"), sum("payment_id", payment)); }
        else {
            assertEquals(201, race.first().status()); counts(race.second().body(), 0, 0, 1, 0); reason(race.second().body(), payment, "MANUAL_REQUIRED");
            assertEquals(new BigDecimal("10.00"), sum("payment_id", payment));
            assertTrue(events(race.contender()).stream().anyMatch(e -> e.stage().equals("afterLock") && e.parent() == Parent.PAYMENT));
            assertTrue(events(race.contender()).stream().noneMatch(e -> e.stage().equals("inserted")));
        }
        assertEquals(1, count("allocations")); facts(1);
    }

    static java.util.stream.Stream<Arguments> invoiceRaces() {
        return java.util.stream.Stream.of(Arguments.of(false,"80"), Arguments.of(true,"80"), Arguments.of(false,"100"), Arguments.of(true,"100"));
    }
    @ParameterizedTest @MethodSource("invoiceRaces")
    void a05ManualAndAutomaticShareInvoiceLockAndFreshBalanceRechecksInBothOrders(boolean autoFirst, String manualAmount) throws Exception {
        long invoice = fixture.invoice("I", "100"), automatic = targeted("AUTO", "60", "demo-invoices", "I"), manual = fixture.payment("MANUAL", manualAmount);
        var race = compete(autoFirst ? () -> automatic(automatic) : () -> manual(manual, invoice, manualAmount),
                autoFirst ? () -> manual(manual, invoice, manualAmount) : () -> automatic(automatic), Parent.INVOICE, invoice);
        BigDecimal expectedRemaining = autoFirst ? new BigDecimal("40.00") : new BigDecimal("100.00").subtract(new BigDecimal(manualAmount));
        assertEquals(expectedRemaining, balances(race.contender()).invoices().getFirst().remaining());
        assertLockOrder(race.winner(), autoFirst ? automatic : manual, invoice); assertLockOrder(race.contender(), autoFirst ? manual : automatic, invoice);
        if (autoFirst) { counts(race.first().body(), 1, 0, 0, 0); assertEquals(409, race.second().status()); assertEquals(new BigDecimal("60.00"), sum("invoice_id", invoice)); }
        else { assertEquals(201, race.first().status()); counts(race.second().body(), 0, 0, 1, 0); reason(race.second().body(), automatic, manualAmount.equals("100") ? "TARGET_ALREADY_PAID" : "OVERPAYMENT"); assertEquals(new BigDecimal(manualAmount).setScale(2), sum("invoice_id", invoice)); }
        assertEquals(1, count("allocations")); facts(1);
    }

    @Test void a05TwoAutomaticPaymentsShareInvoiceAndSecondCannotPartiallyAllocateToFit() throws Exception {
        long invoice = fixture.invoice("I", "100"), first = targeted("P1", "60", "demo-invoices", "I"), second = targeted("P2", "60", "demo-invoices", "I");
        var race = compete(() -> automatic(first), () -> automatic(second), Parent.INVOICE, invoice);
        counts(race.first().body(), 1, 0, 0, 0); counts(race.second().body(), 0, 0, 1, 0); reason(race.second().body(), second, "OVERPAYMENT");
        assertEquals(new BigDecimal("40.00"), balances(race.contender()).invoices().getFirst().remaining());
        assertEquals(new BigDecimal("0.00"), sum("payment_id", second)); assertEquals(new BigDecimal("60.00"), sum("invoice_id", invoice)); assertEquals(1, count("allocations")); facts(1);
    }

    @ParameterizedTest @ValueSource(strings={"inserted","beforeCommit"})
    void a03ActualAllocationAndFinalWritesFaultRollbackAllThreeFactsAndRunContinues(String stage) throws Exception {
        fixture.invoice("I1", "100"); fixture.invoice("I2", "100");
        long first = targeted("P1", "60", "demo-invoices", "I1"), next = targeted("P2", "60", "demo-invoices", "I2");
        AtomicReference<Context> failed = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals("started")) failed.compareAndSet(null, event.context());
            if (event.context().equals(failed.get()) && event.stage().equals(stage)) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE operation_id=?", Integer.class, failed.get().operationId()));
                assertEquals(stage.equals("beforeCommit") ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=?", Integer.class, failed.get().operationId()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_id=?", Integer.class, failed.get().operationId()));
                if (stage.equals("beforeCommit")) assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_id=? AND response_status=201 AND response_body IS NOT NULL", Integer.class, failed.get().operationId()));
                throw new IllegalStateException("synthetic automatic fault after actual SQL");
            }
        };
        var run = run(first, next); counts(run, 1, 0, 0, 1); assertEquals("INTERNAL_ERROR", item(run, first).get("code").asString()); assertFalse(item(run, first).get("retryable").asBoolean());
        assertEquals("APPLIED", item(run, next).get("status").asString());
        for (String table : List.of("allocations", "audit_events", "idempotency_records")) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE operation_id=?", Integer.class, failed.get().operationId()));
        assertEquals(new BigDecimal("0.00"), sum("payment_id", first)); facts(1);
        observer.reset(); var retry = run(first, next); counts(retry, 1, 1, 0, 0); assertTrue(item(retry, next).get("replayed").asBoolean()); assertEquals(2, count("allocations")); facts(2);
    }

    @Test void actualPaymentLockTimeoutBecomesRetryableFailedItemAndNextItemStillCommits() throws Exception {
        fixture.invoice("I1", "100"); fixture.invoice("I2", "100");
        long locked = targeted("LOCKED", "60", "demo-invoices", "I1"), next = targeted("NEXT", "60", "demo-invoices", "I2");
        ExecutorService workers = Executors.newSingleThreadExecutor(); Future<JsonNode> pending = null;
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false); int pid;
            try (var query = held.createStatement(); var row = query.executeQuery("SELECT pg_backend_pid()")) { assertTrue(row.next()); pid = row.getInt(1); }
            try (var lock = held.prepareStatement("SELECT id FROM payments WHERE id=? FOR UPDATE")) { lock.setLong(1, locked); try (var row = lock.executeQuery()) { assertTrue(row.next()); } }
            CountDownLatch reached = new CountDownLatch(1); AtomicReference<Context> context = new AtomicReference<>();
            observer.hook = event -> { if (event.stage().equals("started")) context.compareAndSet(null, event.context()); if (event.stage().equals("beforeLock") && event.parent() == Parent.PAYMENT && event.parentId() == locked) reached.countDown(); };
            pending = workers.submit(() -> run(locked, next)); await(reached, "AUTO HTTP reached externally held payment lock"); awaitBlockedBy(context.get().backendPid(), pid);
            var result = pending.get(7, TimeUnit.SECONDS); counts(result, 1, 0, 0, 1);
            assertEquals("RETRYABLE_DATABASE_ERROR", item(result, locked).get("code").asString()); assertTrue(item(result, locked).get("retryable").asBoolean());
            assertEquals("APPLIED", item(result, next).get("status").asString()); assertEquals(new BigDecimal("0.00"), sum("payment_id", locked));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE key=?", Integer.class, "auto:v1:" + locked)); facts(1);
            held.rollback(); observer.reset(); var retry = run(locked, next); counts(retry, 1, 1, 0, 0); facts(2);
            System.out.println("M08 actual timeout evidence: AUTO backend " + context.get().backendPid() + " blocked by " + pid + "; retryable FAILED item/next actual commit/rerun safe");
        } finally { cleanup(workers, pending); }
    }

    record Race(Response first, Response second, Context winner, Context contender) {}
    Race compete(Callable<Response> first, Callable<Response> second, Parent shared, long id) throws Exception {
        CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1), secondBeforeLock = new CountDownLatch(1);
        AtomicReference<Context> winner = new AtomicReference<>(), contender = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals("started")) { assertEquals("read committed", event.isolation()); if (!winner.compareAndSet(null, event.context())) contender.set(event.context()); }
            if (event.stage().equals("balances") && event.context().equals(winner.get())) { paused.countDown(); await(release, "release winner after real shared-parent blocker observation"); }
            if (event.stage().equals("beforeLock") && event.context().equals(contender.get()) && event.parent() == shared && event.parentId() == id) secondBeforeLock.countDown();
        };
        ExecutorService workers = Executors.newFixedThreadPool(2); Future<Response> a = null, b = null;
        try {
            a = workers.submit(first); await(paused, "winner holds all parent locks and fresh balances");
            b = workers.submit(second); await(secondBeforeLock, "second reached common parent lock");
            awaitBlockedBy(contender.get().backendPid(), winner.get().backendPid()); assertEquals(0, count("allocations"));
            release.countDown(); var result = new Race(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS), winner.get(), contender.get());
            System.out.println("M08 shared parent evidence: " + shared + " " + id + ", " + contender.get().operationType() + " backend " + contender.get().backendPid() + " blocked by " + winner.get().operationType() + " backend " + winner.get().backendPid() + "; fresh sums and qualification rechecked");
            return result;
        } finally { release.countDown(); cleanup(workers, a, b); }
    }
    List<ControlledTransactionObserver.Event> events(Context context) { return observer.events.stream().filter(e -> e.context().equals(context)).toList(); }
    ControlledTransactionObserver.Event balances(Context context) { return events(context).stream().filter(e -> e.stage().equals("balances")).findFirst().orElseThrow(); }
    void assertLockOrder(Context context, long payment, long invoice) {
        var events = events(context); var locks = events.stream().filter(e -> e.stage().equals("afterLock")).toList();
        assertEquals(2, locks.size()); assertEquals(Parent.PAYMENT, locks.getFirst().parent()); assertEquals(payment, locks.getFirst().parentId());
        assertEquals(Parent.INVOICE, locks.getLast().parent()); assertEquals(invoice, locks.getLast().parentId()); assertTrue(events.indexOf(balances(context)) > events.indexOf(locks.getLast()));
    }
    void awaitBlockedBy(int blocked, int blocker) throws InterruptedException {
        assertNotEquals(blocked, blocker); long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        do { if (jdbc.queryForList("SELECT unnest(pg_blocking_pids(?))", Integer.class, blocked).contains(blocker)) { assertEquals("Lock", jdbc.queryForObject("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?", String.class, blocked)); return; } Thread.sleep(20); } while (System.nanoTime() < deadline);
        fail("Real PostgreSQL blocker relation was not observed");
    }
    static void await(CountDownLatch latch, String description) { try { assertTrue(latch.await(8, TimeUnit.SECONDS), description); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(description, error); } }
    static void cleanup(ExecutorService workers, Future<?>... futures) throws InterruptedException { for (var future : futures) if (future != null && !future.isDone()) future.cancel(true); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
}
