package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.allocation.*;
import dev.reconcilekit.api.ApiException;
import dev.reconcilekit.transaction.TransactionObserver.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** M04 real-PG proof: no method-wide transaction, no SQL mocks, bounded worker cleanup. */
@SpringBootTest
@Import(TransactionTestConfiguration.class)
class AllocationTransactionsTest extends PostgreSqlTestBase {
    @Autowired AllocationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ControlledTransactionObserver observer;
    LedgerFixture fixture;
    @BeforeEach void reset() { observer.reset(); fixture = new LedgerFixture(jdbc); }
    @AfterEach void resetHook() { observer.reset(); }

    static AllocationCommand.Target target(long invoice, String amount) {
        return new AllocationCommand.Target(invoice, new BigDecimal(amount));
    }
    static AllocationCommand command(long payment, AllocationCommand.Target... targets) {
        return new AllocationCommand(payment, List.of(targets));
    }
    record Outcome(AllocationService.Applied applied, RuntimeException error) {}
    Outcome invoke(AllocationCommand command) {
        try { return new Outcome(service.allocate(command), null); }
        catch (RuntimeException error) { return new Outcome(null, error); }
    }

    @Test void t05CompetingPaymentTransactionsReadTheWinnersCommittedAllocation() throws Exception {
        long payment = fixture.payment("P", "100");
        long firstInvoice = fixture.invoice("I1", "100"), secondInvoice = fixture.invoice("I2", "100");
        var contexts = compete(command(payment, target(firstInvoice, "80")), command(payment, target(secondInvoice, "80")), Parent.PAYMENT, payment, false);
        assertEquals(new BigDecimal("80.00"), sum("payment_id", payment));
        assertEquals(new BigDecimal("20.00"), balanceEvent(contexts.get(1)).paymentRemaining());
        assertEquals(new BigDecimal("0.00"), sum("invoice_id", secondInvoice));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        independentInvariants();
    }

    @Test void t06CompetingInvoiceTransactionsNeedTheSharedInvoiceLock() throws Exception {
        long firstPayment = fixture.payment("P1", "80"), secondPayment = fixture.payment("P2", "80");
        long invoice = fixture.invoice("I", "100");
        var contexts = compete(command(firstPayment, target(invoice, "80")), command(secondPayment, target(invoice, "80")), Parent.INVOICE, invoice, false);
        assertEquals(new BigDecimal("80.00"), sum("invoice_id", invoice));
        assertEquals(new BigDecimal("20.00"), balanceEvent(contexts.get(1)).invoices().getFirst().remaining());
        assertEquals(new BigDecimal("0.00"), sum("payment_id", secondPayment));
        independentInvariants();
    }

    @Test void t09FailureAfterFirstActualInsertRollsBackEveryAllocation() {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "60"), second = fixture.invoice("I2", "60");
        AtomicReference<UUID> failedOperation = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals("inserted") && event.insertedCount() == 1) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE id=? AND operation_id=?", Integer.class, event.allocationId(), event.context().operationId()));
                failedOperation.set(event.context().operationId());
                throw new IllegalStateException("synthetic test fault after actual INSERT");
            }
        };
        assertThrows(IllegalStateException.class, () -> service.allocate(command(payment, target(second, "40"), target(first, "40"))));
        assertNotNull(failedOperation.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE operation_id=?", Integer.class, failedOperation.get()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertEquals(new BigDecimal("0.00"), sum("payment_id", payment));
        observer.hook = ignored -> {};
        assertEquals(2, service.allocate(command(payment, target(first, "40"), target(second, "40"))).allocationIds().size());
        independentInvariants();
    }

    @Test void t09SecondInsufficientInvoiceRejectsTheWholeRequestBeforeInserts() {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "60"), second = fixture.invoice("I2", "30");
        assertEquals(409, assertThrows(ApiException.class, () -> service.allocate(command(payment, target(first, "40"), target(second, "40")))).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        assertTrue(observer.events.stream().noneMatch(event -> event.stage().equals("inserted")));
        independentInvariants();
    }

    @Test void totalRequestCannotExceedPaymentEvenWhenEveryTargetIndividuallyFits() {
        long payment = fixture.payment("P", "60"), first = fixture.invoice("I1", "100"), second = fixture.invoice("I2", "100");
        assertEquals(409, assertThrows(ApiException.class, () -> service.allocate(command(payment, target(first, "40"), target(second, "40")))).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        independentInvariants();
    }

    @Test void t10SamePairAppendIsLegalButDuplicateTargetsAreRejectedBeforeLocks() {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        var first = service.allocate(command(payment, target(invoice, "30")));
        var second = service.allocate(command(payment, target(invoice, "20")));
        assertNotEquals(first.operationId(), second.operationId());
        assertNotEquals(first.allocationIds().getFirst(), second.allocationIds().getFirst());
        assertEquals(new BigDecimal("50.00"), sum("payment_id", payment));
        observer.reset();
        assertEquals(400, assertThrows(ApiException.class, () -> service.allocate(command(payment, target(invoice, "1"), target(invoice, "2")))).status());
        assertTrue(observer.events.isEmpty(), "Validation must precede all observation/lock SQL");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        independentInvariants();
    }

    @Test void invalidServiceCommandsAreRejectedBeforeLocks() {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        List<AllocationCommand.Target> tooMany = java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(id -> target(id, "1")).toList();
        for (AllocationCommand invalid : List.of(command(payment), new AllocationCommand(payment, tooMany),
                command(0, target(invoice, "1")), command(payment, target(0, "1")),
                command(payment, new AllocationCommand.Target(invoice, null)))) {
            assertEquals(400, assertThrows(ApiException.class, () -> service.allocate(invalid)).status());
        }
        assertEquals(400, assertThrows(ApiException.class, () -> service.allocate(new AllocationCommand(payment, null))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.allocate(command(payment, target(invoice, "0.001")))).status());
        assertTrue(observer.events.isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
        independentInvariants();
    }

    @Test void twentyDistinctTargetsAreAcceptedAndNormalizedExactly() {
        long payment = fixture.payment("P", "100");
        var targets = new ArrayList<AllocationCommand.Target>();
        for (int n = 0; n < 20; n++) targets.add(target(fixture.invoice("I" + n, "10"), "1.000"));
        Collections.reverse(targets);
        var result = service.allocate(new AllocationCommand(payment, targets));
        assertEquals(20, result.allocationIds().size());
        assertEquals("20.00", result.payment().allocated());
        assertEquals("80.00", result.payment().remaining());
        assertEquals(20, jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE amount=1.00", Integer.class));
        assertLocksThenBalances(observer.events.getFirst().context(), payment, targets.stream().map(AllocationCommand.Target::invoiceId).sorted().toList());
        independentInvariants();
    }

    @Test void t15ReversedInputStillLocksPaymentThenAllInvoiceIdsAscending() {
        long payment = fixture.payment("P", "100"), first = fixture.invoice("I1", "100"), second = fixture.invoice("I2", "100");
        var result = service.allocate(command(payment, target(second, "20.000"), target(first, "10")));
        assertEquals(List.of(first, second), result.invoices().stream().map(view -> view.id()).toList());
        assertLocksThenBalances(observer.events.getFirst().context(), payment, List.of(first, second));
        independentInvariants();
    }

    @Test void t15OppositeInputsActuallyOverlapAndBothCompleteInTheSameLockOrder() throws Exception {
        long p1 = fixture.payment("P1", "100"), p2 = fixture.payment("P2", "100");
        long i1 = fixture.invoice("I1", "100"), i2 = fixture.invoice("I2", "100");
        var contexts = compete(command(p1, target(i2, "10"), target(i1, "10")), command(p2, target(i1, "20"), target(i2, "20")), Parent.INVOICE, i1, true);
        assertLocksThenBalances(contexts.get(0), p1, List.of(i1, i2));
        assertLocksThenBalances(contexts.get(1), p2, List.of(i1, i2));
        assertEquals(new BigDecimal("30.00"), sum("invoice_id", i1));
        assertEquals(new BigDecimal("30.00"), sum("invoice_id", i2));
        assertEquals(new BigDecimal("90.00"), balanceEvent(contexts.get(1)).invoices().getFirst().remaining());
        independentInvariants();
    }

    @Test void t15ExternalJdbcLockProducesRealTimeoutAndRetryAfterReleaseSucceeds() throws Exception {
        long payment = fixture.payment("P", "100"), invoice = fixture.invoice("I", "100");
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Future<Outcome> pending = null;
        try (Connection fault = dataSource.getConnection()) {
            fault.setAutoCommit(false);
            int faultPid;
            try (var statement = fault.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) { assertTrue(rows.next()); faultPid = rows.getInt(1); }
            try (var statement = fault.prepareStatement("SELECT id FROM payments WHERE id=? FOR UPDATE")) { statement.setLong(1, payment); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); } }
            CountDownLatch beforeLock = new CountDownLatch(1);
            AtomicReference<Context> context = new AtomicReference<>();
            observer.hook = event -> {
                if (event.stage().equals("started")) context.set(event.context());
                if (event.stage().equals("beforeLock") && event.parent() == Parent.PAYMENT) beforeLock.countDown();
            };
            pending = workers.submit(() -> invoke(command(payment, target(invoice, "40"))));
            await(beforeLock, "timeout contender reached payment lock");
            assertNotEquals(faultPid, context.get().backendPid());
            awaitBlockedBy(context.get().backendPid(), faultPid);
            Outcome timeout = pending.get(6, TimeUnit.SECONDS);
            assertNull(timeout.applied());
            DataAccessException databaseFailure = assertInstanceOf(DataAccessException.class, timeout.error());
            assertEquals("55P03", sqlState(timeout.error()));
            dev.reconcilekit.api.DatabaseErrorAssertions.retryable(databaseFailure);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            fault.rollback();
            observer.reset();
            var result = service.allocate(command(payment, target(invoice, "40")));
            assertEquals("60.00", result.payment().remaining());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
            assertEquals("0", jdbc.queryForObject("SHOW lock_timeout", String.class), "SET LOCAL must not leak to the pool");
            independentInvariants();
            System.out.println("M04 timeout evidence: actual SQLSTATE55P03, backend " + context.get().backendPid() + " blocked by " + faultPid + "; retry committed once");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    List<Context> compete(AllocationCommand first, AllocationCommand second, Parent sharedParent, long sharedId, boolean bothSucceed) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch winnerPaused = new CountDownLatch(1), releaseWinner = new CountDownLatch(1), secondBeforeLock = new CountDownLatch(1);
        AtomicReference<Context> winner = new AtomicReference<>(), contender = new AtomicReference<>();
        Future<Outcome> firstResult = null, secondResult = null;
        observer.hook = event -> {
            if (event.stage().equals("started")) {
                assertEquals("read committed", event.isolation());
                if (!winner.compareAndSet(null, event.context())) contender.set(event.context());
            }
            if (event.stage().equals("balances") && event.context().equals(winner.get())) {
                winnerPaused.countDown();
                await(releaseWinner, "release paused winner after database blocking proof");
            }
            if (event.stage().equals("beforeLock") && event.context().equals(contender.get()) && event.parent() == sharedParent && event.parentId() == sharedId)
                secondBeforeLock.countDown();
        };
        try {
            firstResult = workers.submit(() -> invoke(first));
            await(winnerPaused, "winner acquired all locks and read fresh balances");
            secondResult = workers.submit(() -> invoke(second));
            await(secondBeforeLock, "second transaction reached shared row lock");
            assertNotEquals(winner.get().backendPid(), contender.get().backendPid());
            awaitBlockedBy(contender.get().backendPid(), winner.get().backendPid());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class), "Winner has not committed yet");
            releaseWinner.countDown();
            Outcome won = firstResult.get(8, TimeUnit.SECONDS), lost = secondResult.get(8, TimeUnit.SECONDS);
            assertNull(won.error());
            assertNotNull(won.applied());
            if (bothSucceed) { assertNull(lost.error()); assertNotNull(lost.applied()); }
            else { assertNull(lost.applied()); assertEquals(409, assertInstanceOf(ApiException.class, lost.error()).status()); }
            assertLocksThenBalances(winner.get(), first.paymentId(), first.allocations().stream().map(AllocationCommand.Target::invoiceId).sorted().toList());
            assertLocksThenBalances(contender.get(), second.paymentId(), second.allocations().stream().map(AllocationCommand.Target::invoiceId).sorted().toList());
            System.out.println("M04 concurrency evidence: " + sharedParent + " " + sharedId + ", contender backend " + contender.get().backendPid() + " blocked by " + winner.get().backendPid() + "; result " + (bothSucceed ? "two commits" : "one commit/one409"));
            return List.of(winner.get(), contender.get());
        } finally {
            releaseWinner.countDown();
            if (firstResult != null && !firstResult.isDone()) firstResult.cancel(true);
            if (secondResult != null && !secondResult.isDone()) secondResult.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    void awaitBlockedBy(int blockedPid, int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        do {
            List<Integer> blockers = jdbc.queryForList("SELECT unnest(pg_blocking_pids(?))", Integer.class, blockedPid);
            if (blockers.contains(blockerPid)) {
                assertEquals("Lock", jdbc.queryForObject("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?", String.class, blockedPid));
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("No real PostgreSQL block observed: " + blockedPid + " should wait for " + blockerPid);
    }
    static void await(CountDownLatch latch, String description) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS), description); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(description, error); }
    }
    ControlledTransactionObserver.Event balanceEvent(Context context) {
        return observer.events.stream().filter(event -> event.context().equals(context) && event.stage().equals("balances")).findFirst().orElseThrow();
    }
    void assertLocksThenBalances(Context context, long payment, List<Long> invoices) {
        var events = observer.events.stream().filter(event -> event.context().equals(context)).toList();
        var locks = events.stream().filter(event -> event.stage().equals("afterLock")).toList();
        assertEquals(invoices.size() + 1, locks.size());
        assertEquals(Parent.PAYMENT, locks.getFirst().parent());
        assertEquals(payment, locks.getFirst().parentId());
        assertEquals(invoices, locks.stream().skip(1).map(event -> event.parentId()).toList());
        assertTrue(locks.stream().skip(1).allMatch(event -> event.parent() == Parent.INVOICE));
        assertTrue(events.indexOf(balanceEvent(context)) > events.indexOf(locks.getLast()));
        assertEquals("read committed", events.getFirst().isolation());
    }
    BigDecimal sum(String foreign, long id) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0)::numeric(19,2) FROM allocations WHERE " + foreign + "=?", BigDecimal.class, id);
    }
    void independentInvariants() {
        fixture.invariants();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM allocations a LEFT JOIN payments p ON p.id=a.payment_id LEFT JOIN invoices i ON i.id=a.invoice_id WHERE p.id IS NULL OR i.id IS NULL", Integer.class));
        int operations = jdbc.queryForObject("SELECT COUNT(DISTINCT operation_id) FROM allocations", Integer.class);
        assertEquals(operations, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_type='MANUAL_ALLOCATION'", Integer.class));
        assertEquals(operations, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_type='ALLOCATION_COMMITTED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE response_status IS NULL OR response_body IS NULL", Integer.class));
    }
    static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql.getSQLState();
        fail("Actual database SQLSTATE is missing");
        return null;
    }
}
