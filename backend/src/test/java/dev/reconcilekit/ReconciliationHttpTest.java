package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.allocation.AllocationCommand;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** A01–A05 HTTP contracts and read-only reasons backed by committed PostgreSQL facts. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TransactionTestConfiguration.class, ReconciliationTestConfiguration.class})
class ReconciliationHttpTest extends ReconciliationTestSupport {
    @Autowired @Qualifier("ledgerTransactions") PlatformTransactionManager transactions;

    @Test void a01ExactExplicitPaymentCommitsWhole60AndInvoiceRemains40() throws Exception {
        long invoice = fixture.invoice("INV-103", "100"), payment = targeted("PAY-002", "60", "demo-invoices", "INV-103");
        assertTrue(pending().get(payment).get("reason").isNull());
        var result = run(payment); counts(result, 1, 0, 0, 0);
        assertEquals("APPLIED", item(result, payment).get("status").asString());
        assertFalse(item(result, payment).get("replayed").asBoolean());
        assertEquals(new BigDecimal("60.00"), sum("payment_id", payment)); assertEquals(new BigDecimal("60.00"), sum("invoice_id", invoice));
        var invoiceView = get("/api/v1/invoices").get("items").get(0);
        assertEquals("40.00", invoiceView.get("remaining").asString()); assertEquals("PARTIALLY_PAID", invoiceView.get("status").asString());
        var paymentView = get("/api/v1/payments").get("items").get(0);
        assertEquals("0.00", paymentView.get("remaining").asString()); assertEquals("FULLY_ALLOCATED", paymentView.get("status").asString());
        assertTrue(pending().isEmpty()); assertEquals(1, count("allocations")); facts(1);
    }

    @Test void a02FreeBatchReferenceIsNeverGuessedAndMissingTargetCanBeImportedThenRerun() throws Exception {
        fixture.invoice("BATCH-01", "200");
        long free = fixture.payment("PAY-001", "200"); jdbc.update("UPDATE payments SET raw_reference='BATCH-01' WHERE id=?", free);
        long missing = targeted("MISSING", "60", "demo-invoices", "LATER");
        var first = run(free, missing); counts(first, 0, 0, 2, 0);
        reason(first, free, "NO_EXPLICIT_TARGET"); reason(first, missing, "NO_TARGET");
        assertEquals("NO_EXPLICIT_TARGET", pending().get(free).get("reason").asString());
        assertEquals("NO_TARGET", pending().get(missing).get("reason").asString());
        assertTrue(observer.events.stream().anyMatch(e -> e.stage().equals("claimed")), "No-write paths must roll back their real preliminary claims");
        assertEquals(0, count("allocations")); facts(0);
        var imported = post("/api/v1/imports/invoices", "{\"source\":\"demo-invoices\",\"rows\":[{\"source\":\"demo-invoices\",\"external_invoice_id\":\"LATER\",\"amount\":\"100\",\"currency\":\"GBP\",\"issued_on\":\"2026-10-01\"}]}");
        assertEquals(201, imported.statusCode(), imported.body());
        var rerun = run(free, missing); counts(rerun, 1, 0, 1, 0); reason(rerun, free, "NO_EXPLICIT_TARGET");
        assertEquals(new BigDecimal("60.00"), sum("payment_id", missing)); assertEquals(new BigDecimal("0.00"), sum("payment_id", free)); facts(1);
    }

    @Test void a04SourceCaseAndPrefixAreExactAndCrossSourceSameNumberDoesNotFallback() throws Exception {
        fixture.invoice("INV-1", "100"); fixture.invoice("INV-10", "100");
        long other = fixture.invoice("OTHER", "100"); jdbc.update("UPDATE invoices SET source='other-source',external_invoice_id='INV-1' WHERE id=?", other);
        long wrongSource = targeted("WRONG", "60", "missing-source", "INV-1");
        long sourceCase = targeted("SOURCE-CASE", "60", "Demo-Invoices", "INV-1");
        long wrongCase = targeted("CASE", "60", "demo-invoices", "inv-1");
        long prefix = targeted("PREFIX", "60", "demo-invoices", "INV");
        long exactOther = targeted("EXACT-OTHER", "60", "other-source", "INV-1");
        var report = run(wrongSource, sourceCase, wrongCase, prefix, exactOther); counts(report, 1, 0, 4, 0);
        for (long p : new long[]{wrongSource, sourceCase, wrongCase, prefix}) reason(report, p, "NO_TARGET");
        assertEquals(other, jdbc.queryForObject("SELECT invoice_id FROM allocations", Long.class));
        assertEquals(new BigDecimal("0.00"), sum("invoice_id", 1)); assertEquals(new BigDecimal("0.00"), sum("invoice_id", 2)); facts(1);
    }

    @Test void a05WholePaymentFitAndManualHistoryReasonsNeverUseMinOrAlterHistory() throws Exception {
        long open = fixture.invoice("OPEN", "100"), settled = fixture.invoice("SETTLED", "100"), partial = fixture.invoice("PARTIAL", "100");
        long settledSeed = fixture.payment("SETTLE", "100"); manual(settledSeed, settled, "100");
        long over = targeted("OVER", "120", "demo-invoices", "OPEN"), paid = targeted("PAID", "60", "demo-invoices", "SETTLED");
        long history = targeted("HISTORY", "60", "demo-invoices", "PARTIAL"); manual(history, partial, "10");
        long fully = targeted("FULL", "60", "demo-invoices", "PARTIAL"); manual(fully, partial, "60");
        var before = jdbc.queryForList("SELECT * FROM allocations ORDER BY id");
        var report = run(over, paid, history, fully); counts(report, 0, 1, 3, 0);
        reason(report, over, "OVERPAYMENT"); reason(report, paid, "TARGET_ALREADY_PAID"); reason(report, history, "MANUAL_REQUIRED");
        var skipped = item(report, fully); assertEquals("SKIPPED", skipped.get("status").asString()); assertTrue(skipped.get("reason").isNull()); assertFalse(skipped.get("replayed").asBoolean());
        assertEquals(before, jdbc.queryForList("SELECT * FROM allocations ORDER BY id")); assertEquals(new BigDecimal("0.00"), sum("invoice_id", open));
        var pending = pending(); assertEquals("OVERPAYMENT", pending.get(over).get("reason").asString()); assertEquals("TARGET_ALREADY_PAID", pending.get(paid).get("reason").asString()); assertEquals("MANUAL_REQUIRED", pending.get(history).get("reason").asString()); assertFalse(pending.containsKey(fully));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_type='AUTO_ALLOCATION'", Integer.class)); facts(3);
    }

    @Test void wholePaymentExactlyFittingAnInvoicesRemainingBalanceIsEligibleDespiteOtherPaymentsHistory() throws Exception {
        long invoice = fixture.invoice("I", "100"), earlier = fixture.payment("EARLIER", "40"); manual(earlier, invoice, "40");
        long payment = targeted("EXACT-FIT", "60", "demo-invoices", "I");
        assertTrue(pending().get(payment).get("reason").isNull());
        var report = run(payment); counts(report, 1, 0, 0, 0);
        assertEquals(new BigDecimal("60.00"), sum("payment_id", payment)); assertEquals(new BigDecimal("100.00"), sum("invoice_id", invoice));
        assertEquals("PAID", get("/api/v1/invoices").get("items").get(0).get("status").asString()); assertTrue(pending().isEmpty()); facts(2);
    }

    @Test void a03OldAutomaticResponseAndStableKeyReplayWithoutNewAuditOrLocks() throws Exception {
        fixture.invoice("I", "100"); long payment = targeted("P", "60", "demo-invoices", "I");
        var applied = run(payment); counts(applied, 1, 0, 0, 0);
        var persisted = jdbc.queryForMap("SELECT * FROM idempotency_records WHERE operation_type='AUTO_ALLOCATION' AND key=?", "auto:v1:" + payment);
        String savedBody = (String) persisted.get("response_body");
        observer.reset(); var replay = run(payment); counts(replay, 0, 1, 0, 0);
        assertNotEquals(applied.get("runId").asString(), replay.get("runId").asString());
        assertTrue(item(replay, payment).get("replayed").asBoolean()); assertEquals(item(applied, payment).get("operationId"), item(replay, payment).get("operationId"));
        assertEquals(item(applied, payment).get("allocationIds"), item(replay, payment).get("allocationIds"));
        assertEquals(persisted, jdbc.queryForMap("SELECT * FROM idempotency_records WHERE operation_type='AUTO_ALLOCATION' AND key=?", "auto:v1:" + payment));
        assertEquals(mapper.readTree(savedBody).get("operationId"), item(replay, payment).get("operationId"));
        assertTrue(observer.events.stream().noneMatch(e -> e.stage().equals("beforeLock") || e.stage().equals("balances") || e.stage().equals("inserted")));
        assertEquals(1, count("allocations")); facts(1);
    }

    @Test void a03InterruptionOutsideTransactionPreservesFirstRealCommitAndRerunFinishesSecond() throws Exception {
        fixture.invoice("I1", "100"); fixture.invoice("I2", "100");
        long first = targeted("P1", "60", "demo-invoices", "I1"), second = targeted("P2", "60", "demo-invoices", "I2");
        runObserver.hook = event -> {
            assertFalse(event.transactionActive());
            if (event.processed() == 1) { assertEquals(new BigDecimal("60.00"), sum("payment_id", first)); assertEquals(1, count("allocations")); facts(1); throw new IllegalStateException("synthetic interruption after committed item"); }
        };
        assertThrows(IllegalStateException.class, () -> runs.run(List.of(first, second)));
        assertEquals(new BigDecimal("0.00"), sum("payment_id", second)); facts(1);
        runObserver.reset(); var rerun = run(first, second); counts(rerun, 1, 1, 0, 0);
        assertTrue(item(rerun, first).get("replayed").asBoolean()); assertEquals("APPLIED", item(rerun, second).get("status").asString());
        assertEquals(2, count("allocations")); assertTrue(runObserver.events.stream().noneMatch(e -> e.transactionActive())); facts(2);
    }

    @Test void invalidRunInputAndClientChosenInternalScopeFailBeforeTransactions() throws Exception {
        for (String body : List.of("{}", "null", "{\"payment_ids\":null}", "{\"payment_ids\":{}}", "{\"payment_ids\":[1,1]}", "{\"payment_ids\":[0]}", "{\"payment_ids\":[-1]}", "{\"payment_ids\":[null]}", "{\"payment_ids\":[\"1\"]}", "{\"payment_ids\":[1.0]}", "{\"payment_ids\":[9223372036854775808]}", "{\"payment_ids\":[1],\"operation_type\":\"AUTO_ALLOCATION\"}", "{\"payment_ids\":[1],\"key\":\"custom\"}")) {
            var response = post("/api/v1/reconciliation/runs", body); assertEquals(400, response.statusCode(), body); assertEquals("m08-evidence", mapper.readTree(response.body()).get("request_id").asString());
        }
        for (int size : new int[]{0, 101}) {
            var response = post("/api/v1/reconciliation/runs", mapper.writeValueAsString(Map.of("payment_ids", java.util.stream.LongStream.rangeClosed(1, size).toArray()))); assertEquals(422, response.statusCode());
        }
        assertThrows(RuntimeException.class, () -> runs.run(Arrays.asList(1L, null)));
        assertThrows(RuntimeException.class, () -> runs.run(List.of(1L, 1L)));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> runs.run(List.of(1L))));
        assertTrue(observer.events.isEmpty()); assertTrue(runObserver.events.isEmpty()); facts(0);
    }

    @Test void hundredDistinctIdsAreAcceptedAndOneMissingItemDoesNotStopOtherPayments() throws Exception {
        var ids = new long[100];
        for (int n = 0; n < 99; n++) ids[n] = fixture.payment("REVIEW-" + n, "1");
        fixture.invoice("LAST", "100"); ids[99] = targeted("LAST", "60", "demo-invoices", "LAST");
        var hundred = run(ids); counts(hundred, 1, 0, 99, 0); assertEquals("APPLIED", item(hundred, ids[99]).get("status").asString()); facts(1);
        var missing = run(Long.MAX_VALUE, ids[99]); counts(missing, 0, 1, 0, 1);
        assertEquals("NOT_FOUND", item(missing, Long.MAX_VALUE).get("code").asString()); assertFalse(item(missing, Long.MAX_VALUE).get("retryable").asBoolean()); facts(1);
    }

    @Test void manualClientCannotUseAnInternalOperationTypeEvenWithAutomaticLookingKey() throws Exception {
        long invoice = fixture.invoice("I", "100"), payment = targeted("P", "60", "demo-invoices", "I");
        String allocation = "{\"payment_id\":" + payment + ",\"allocations\":[{\"invoice_id\":" + invoice + ",\"amount\":\"60\"}]}";
        var forbidden = post("/api/v1/allocations", allocation.substring(0, allocation.length()-1) + ",\"operation_type\":\"AUTO_ALLOCATION\"}", "auto:v1:" + payment);
        assertEquals(400, forbidden.statusCode()); assertTrue(observer.events.isEmpty()); facts(0);
        long other = targeted("OTHER", "20", "demo-invoices", "I");
        var manual = post("/api/v1/allocations", allocation, "auto:v1:" + other);
        assertEquals(201, manual.statusCode(), manual.body());
        var automatic = run(other, payment); counts(automatic, 1, 1, 0, 0);
        assertFalse(item(automatic, payment).get("replayed").asBoolean());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE key=?", Integer.class, "auto:v1:" + other));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE operation_type='AUTO_ALLOCATION' AND key=?", Integer.class, "auto:v1:" + payment)); facts(2);
    }
    void manual(long payment, long invoice, String amount) { allocations.allocate(new AllocationCommand(payment, List.of(new AllocationCommand.Target(invoice, new BigDecimal(amount))))); }
}
