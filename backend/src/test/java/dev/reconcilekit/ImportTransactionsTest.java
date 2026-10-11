package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.importbatch.ImportCommand.Kind;
import dev.reconcilekit.importbatch.AtomicImportService;
import dev.reconcilekit.importbatch.ImportParser;
import dev.reconcilekit.importbatch.ImportObserver.Context;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Committed fixtures, real HTTP, real PostgreSQL blockers and independent rollback observations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ImportTestConfiguration.class)
class ImportTransactionsTest extends PostgreSqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ControlledImportObserver observer;
    @Autowired AtomicImportService atomic;
    @Autowired ImportParser parser;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @BeforeEach void reset() {
        observer.reset();
        jdbc.execute("TRUNCATE audit_events,idempotency_records,allocations,payments,invoices,import_batches RESTART IDENTITY");
    }
    @AfterEach void clearHook() { observer.reset(); }

    @ParameterizedTest @EnumSource(Kind.class)
    void t02SourceIdentityDeduplicatesExactlyAndKeepsFirstBatchAndRowMetadata(Kind kind) throws Exception {
        String source = source(kind);
        var z = row(kind, source, "Z", "60");
        var a = row(kind, source, "  A  ", "60.000");
        var original = post(kind, payload(source, List.of(z, a, row(kind, source, "A", "60.00"))), "first-attempt");
        var report = report(original, 201, "COMMITTED", 3, 2, 1, 0);
        String identity = external(kind);
        var firstRows = jdbc.queryForList("SELECT * FROM " + table(kind) + " ORDER BY " + identity);
        assertEquals(2, firstRows.size());
        assertEquals("A", firstRows.get(0).get(identity));
        assertEquals(2, ((Number) firstRows.get(0).get("source_row")).intValue());
        assertEquals(1, ((Number) firstRows.get(1).get("source_row")).intValue());
        assertEquals(UUID.fromString(report.get("attemptId").asString()), firstRows.get(0).get("first_batch_id"));
        assertEquals(new BigDecimal("60.00"), firstRows.get(0).get("amount"));
        var reordered = post(kind, payload(source, List.of(row(kind, source, "A", "60"), row(kind, source, "Z", "60.00"), row(kind, source, "A", "60.0"))), "changed-request-metadata");
        var replay = report(reordered, 201, "COMMITTED", 3, 0, 3, 0);
        assertNotEquals(report.get("attemptId").asString(), replay.get("attemptId").asString());
        assertEquals(report.get("contentHash").asString(), replay.get("contentHash").asString(), "Row ordering, decimal spelling and request metadata must not change canonical content hash");
        assertEquals(firstRows, jdbc.queryForList("SELECT * FROM " + table(kind) + " ORDER BY " + identity));
        var differentIdentity = post(kind, payload(source, List.of(row(kind, source, "a", "60.00"), row(kind, source, "B", "60"))), "same-amount-new-identities");
        report(differentIdentity, 201, "COMMITTED", 2, 2, 0, 0);
        assertEquals(4, count(table(kind)));
        assertEquals(3, count("audit_events"), "Each committed attempt has its own report/audit, including all duplicates");
        assertImportAudit(report, 2);
        assertImportAudit(replay, 0);
        invariantChecks();
    }

    @Test void paymentSourcesAreScopedAndExplicitTargetNeverUsesRawReferenceOrRequiresAnExistingInvoice() throws Exception {
        var payment = row(Kind.PAYMENTS, "bank-a", "P", "60");
        payment.put("raw_reference", "invoice-like free text INV-UNKNOWN");
        payment.put("target_invoice_source", "other-invoice-source");
        payment.put("target_external_invoice_id", "  INV-UNKNOWN  ");
        report(post(Kind.PAYMENTS, payload("bank-a", List.of(payment)), "bank-a"), 201, "COMMITTED", 1, 1, 0, 0);
        var other = row(Kind.PAYMENTS, "bank-b", "P", "60");
        report(post(Kind.PAYMENTS, payload("bank-b", List.of(other)), "bank-b"), 201, "COMMITTED", 1, 1, 0, 0);
        assertEquals(2, count("payments")); assertEquals(0, count("invoices"));
        var stored = jdbc.queryForMap("SELECT * FROM payments WHERE source='bank-a'");
        assertEquals("other-invoice-source", stored.get("target_invoice_source"));
        assertEquals("INV-UNKNOWN", stored.get("target_external_invoice_id"));
        assertEquals("invoice-like free text INV-UNKNOWN", stored.get("raw_reference"));
        assertNull(jdbc.queryForObject("SELECT target_invoice_source FROM payments WHERE source='bank-b'", String.class));
        assertEquals(0, count("allocations"));
        invariantChecks();
    }

    @Test void paymentMissingAndNullReferencesAreEquivalentButEmptyAndWhitespaceReferencesRemainContent() throws Exception {
        String source = source(Kind.PAYMENTS);
        var missing = row(Kind.PAYMENTS, source, "P", "60");
        var first = report(post(Kind.PAYMENTS, payload(source, List.of(missing)), "missing-reference"), 201, "COMMITTED", 1, 1, 0, 0);
        var explicitNull = row(Kind.PAYMENTS, source, "P", "60.00");
        explicitNull.put("raw_reference", null);
        explicitNull.put("target_invoice_source", null);
        explicitNull.put("target_external_invoice_id", null);
        var duplicate = report(post(Kind.PAYMENTS, payload(source, List.of(explicitNull)), "null-reference"), 201, "COMMITTED", 1, 0, 1, 0);
        assertEquals(first.get("contentHash").asString(), duplicate.get("contentHash").asString());
        for (String reference : List.of("", " ", "not an inferred target")) {
            var changed = row(Kind.PAYMENTS, source, "P", "60"); changed.put("raw_reference", reference);
            var failure = report(post(Kind.PAYMENTS, payload(source, List.of(changed)), "changed-reference"), 409, "REJECTED", 1, 0, 0, 1);
            assertFailedAttemptHasNoBusinessFacts(failure, Kind.PAYMENTS);
        }
        var empty = row(Kind.PAYMENTS, source, "EMPTY", "60"); empty.put("raw_reference", "");
        report(post(Kind.PAYMENTS, payload(source, List.of(empty)), "empty-reference"), 201, "COMMITTED", 1, 1, 0, 0);
        assertEquals("", jdbc.queryForObject("SELECT raw_reference FROM payments WHERE external_payment_id='EMPTY'", String.class));
        assertNull(jdbc.queryForObject("SELECT raw_reference FROM payments WHERE external_payment_id='P'", String.class));
        assertEquals(0, count("allocations"));
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t03ChangedBusinessFieldsNeverOverwriteOriginalFacts(Kind kind) throws Exception {
        String source = source(kind);
        var original = row(kind, source, "EXISTING", "60");
        if (kind == Kind.PAYMENTS) {
            original.put("raw_reference", "original reference");
            original.put("target_invoice_source", "demo-invoices"); original.put("target_external_invoice_id", "INV-1");
        }
        report(post(kind, payload(source, List.of(original)), "seed"), 201, "COMMITTED", 1, 1, 0, 0);
        Map<String, Object> snapshot = jdbc.queryForMap("SELECT * FROM " + table(kind));
        var changes = new ArrayList<Map<String, Object>>();
        var amount = new LinkedHashMap<>(original); amount.put("amount", "61.00"); changes.add(amount);
        var date = new LinkedHashMap<>(original); date.put(kind == Kind.INVOICES ? "issued_on" : "received_on", "2026-10-02"); changes.add(date);
        if (kind == Kind.PAYMENTS) {
            var reference = new LinkedHashMap<>(original); reference.put("raw_reference", "different reference"); changes.add(reference);
            var targetSource = new LinkedHashMap<>(original); targetSource.put("target_invoice_source", "another-source"); changes.add(targetSource);
            var targetId = new LinkedHashMap<>(original); targetId.put("target_external_invoice_id", "INV-2"); changes.add(targetId);
            var noTarget = new LinkedHashMap<>(original); noTarget.remove("target_invoice_source"); noTarget.remove("target_external_invoice_id"); changes.add(noTarget);
        }
        for (var changed : changes) {
            var failure = report(post(kind, payload(source, List.of(changed)), "changed-content"), 409, "REJECTED", 1, 0, 0, 1);
            assertEquals(snapshot, jdbc.queryForMap("SELECT * FROM " + table(kind)));
            assertTrue(failure.get("rowErrors").size() > 0);
            assertFailedAttemptHasNoBusinessFacts(failure, kind);
        }
        assertEquals(1, count("audit_events"));
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t04DuplicateNewAndLateConflictingRowsRejectEveryNewRow(Kind kind) throws Exception {
        String source = source(kind);
        report(post(kind, payload(source, List.of(row(kind, source, "A-EXISTING", "60"), row(kind, source, "Z-CONFLICT", "60"))), "seed-mixed"), 201, "COMMITTED", 2, 2, 0, 0);
        var baseline = jdbc.queryForList("SELECT * FROM " + table(kind) + " ORDER BY id");
        var response = post(kind, payload(source, List.of(row(kind, source, "A-EXISTING", "60.00"), row(kind, source, "M-NEW", "60"), row(kind, source, "Z-CONFLICT", "61"))), "mixed-batch");
        var failed = report(response, 409, "REJECTED", 3, 0, 0, 3);
        assertEquals(baseline, jdbc.queryForList("SELECT * FROM " + table(kind) + " ORDER BY id"));
        assertEquals(1, count("audit_events"));
        UUID attempt = UUID.fromString(failed.get("attemptId").asString());
        assertTrue(observer.events.stream().anyMatch(event -> event.context().attemptId().equals(attempt) && event.stage().equals("row") && event.inserted() && event.row().externalId().equals("M-NEW")), "Must prove a real INSERT happened before the late conflict");
        assertFailedAttemptHasNoBusinessFacts(failed, kind);
        assertSeparateFailureTransaction(attempt);
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void batchInternalIdentityConflictRejectsBeforeAnyBusinessTransaction(Kind kind) throws Exception {
        String source = source(kind);
        var failure = report(post(kind, payload(source, List.of(row(kind, source, "P", "60"), row(kind, source, "P", "61"))), "internal-conflict"), 409, "REJECTED", 2, 0, 0, 2);
        assertTrue(observer.events.stream().noneMatch(event -> event.stage().equals("started")));
        assertEquals(0, count(table(kind)));
        assertFailedAttemptHasNoBusinessFacts(failure, kind);
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t11OneAndThousandRowsSucceedZeroAndThousandOneRejectAtomically(Kind kind) throws Exception {
        String source = source(kind);
        report(post(kind, payload(source, List.of(row(kind, source, "ONE", "0.01"))), "one-row"), 201, "COMMITTED", 1, 1, 0, 0);
        var thousand = new ArrayList<Map<String, Object>>();
        for (int n = 0; n < 1000; n++) thousand.add(row(kind, source, "ROW-" + n, "1.000"));
        report(post(kind, payload(source, thousand), "thousand-rows"), 201, "COMMITTED", 1000, 1000, 0, 0);
        assertEquals(1001, count(table(kind)));
        var empty = report(post(kind, payload(source, List.of()), "zero-rows"), 422, "REJECTED", 0, 0, 0, 0);
        assertFailedAttemptHasNoBusinessFacts(empty, kind);
        thousand.add(row(kind, source, "ROW-1000", "1"));
        var tooMany = report(post(kind, payload(source, thousand), "thousand-one"), 422, "REJECTED", 1001, 0, 0, 1001);
        assertFailedAttemptHasNoBusinessFacts(tooMany, kind);
        assertEquals(1001, count(table(kind)));
        assertEquals(2, count("audit_events"));
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t11HttpFieldContractRejectsWithoutRoundingOrPartialImport(Kind kind) throws Exception {
        String source = source(kind);
        record Invalid(String field, Object value, boolean remove, int status) {}
        var invalids = new ArrayList<Invalid>();
        for (String value : List.of("0", "-1", "0.001", "1000000000000.00", "NaN", "Infinity", "1e2", "£1", "")) invalids.add(new Invalid("amount", value, false, 422));
        invalids.add(new Invalid("amount", 60, false, 400)); invalids.add(new Invalid("amount", null, false, 400));
        for (String value : List.of("EUR", "gbp", "")) invalids.add(new Invalid("currency", value, false, 422));
        invalids.add(new Invalid("currency", null, false, 400));
        String dateField = kind == Kind.INVOICES ? "issued_on" : "received_on";
        for (String value : List.of("2025-02-29", "2026-02-30", "2026-1-2", "2026-10-01T00:00:00Z", "0000-01-01", "")) invalids.add(new Invalid(dateField, value, false, 422));
        invalids.add(new Invalid(dateField, null, false, 400));
        invalids.add(new Invalid(external(kind), " ", false, 422)); invalids.add(new Invalid(external(kind), "X".repeat(101), false, 422));
        invalids.add(new Invalid(external(kind), null, false, 400)); invalids.add(new Invalid(external(kind), null, true, 400));
        invalids.add(new Invalid("source", "wrong-row-source", false, 422));
        invalids.add(new Invalid("source", "bad\u0000source", false, 422));
        invalids.add(new Invalid(external(kind), "bad\u0000id", false, 422));
        invalids.add(new Invalid("unknown_field", "not accepted", false, 400));
        for (var invalid : invalids) {
            var changed = row(kind, source, "INVALID", "60");
            if (invalid.remove()) changed.remove(invalid.field()); else changed.put(invalid.field(), invalid.value());
            var failed = report(post(kind, payload(source, List.of(row(kind, source, "A-VALID", "60"), changed)), "contract-error"), invalid.status(), "REJECTED", 2, 0, 0, 2);
            assertEquals(0, count(table(kind)), invalid.field() + "=" + invalid.value());
            assertEquals(0, count("audit_events"));
            assertTrue(failed.get("rowErrors").size() > 0);
            assertFailedAttemptHasNoBusinessFacts(failed, kind);
        }
        invariantChecks();
    }

    @Test void invoiceAuthorityAndPaymentTargetPairRulesAreExplicit() throws Exception {
        var invoice = row(Kind.INVOICES, "other-invoices", "I", "60");
        var unauthorized = report(post(Kind.INVOICES, payload("other-invoices", List.of(invoice)), "wrong-authority"), 422, "REJECTED", 1, 0, 0, 1);
        assertFailedAttemptHasNoBusinessFacts(unauthorized, Kind.INVOICES);
        var targets = List.of(
                new Object[]{"demo-invoices", null, 400}, new Object[]{null, "I", 400},
                new Object[]{"", "", 422}, new Object[]{"  ", "I", 422},
                new Object[]{"demo-invoices", " ", 422}, new Object[]{"demo-invoices", "X".repeat(101), 422},
                new Object[]{123, "I", 400}, new Object[]{"demo-invoices", 123, 400});
        for (Object[] values : targets) {
            var payment = row(Kind.PAYMENTS, source(Kind.PAYMENTS), "P", "60");
            payment.put("target_invoice_source", values[0]); payment.put("target_external_invoice_id", values[1]);
            var failed = report(post(Kind.PAYMENTS, payload(source(Kind.PAYMENTS), List.of(payment)), "target-pair"), (int) values[2], "REJECTED", 1, 0, 0, 1);
            assertFailedAttemptHasNoBusinessFacts(failed, Kind.PAYMENTS);
        }
        assertEquals(0, count("invoices")); assertEquals(0, count("payments")); assertEquals(0, count("audit_events"));
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void normalizedBatchAndRowSourcesAgreeWhileInvalidEnvelopeSourceCannotCreateBusinessRows(Kind kind) throws Exception {
        String canonical = source(kind);
        var normalized = row(kind, "  " + canonical + "  ", "  ID  ", "60");
        var saved = report(post(kind, payload(" " + canonical + " ", List.of(normalized)), "normalized-source"), 201, "COMMITTED", 1, 1, 0, 0);
        assertEquals(canonical, saved.get("source").asString());
        assertEquals(canonical, jdbc.queryForObject("SELECT source FROM " + table(kind), String.class));
        assertEquals("ID", jdbc.queryForObject("SELECT " + external(kind) + " FROM " + table(kind), String.class));
        for (String invalid : List.of(" ", "X".repeat(101), "bad\u0000source")) {
            var rejected = post(kind, payload(invalid, List.of(row(kind, invalid, "NEW", "60"))), "invalid-envelope-source");
            assertEquals(422, rejected.statusCode()); assertError(rejected, "invalid-envelope-source");
            assertEquals(1, count(table(kind))); assertEquals(1, count("audit_events"));
        }
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void entryJsonAndByteFailuresHaveNoBusinessWritesAndDoNotRequireAttempts(Kind kind) throws Exception {
        String path = importPath(kind);
        for (String body : List.of("{bad-json", "{}", "{\"source\":null,\"rows\":[]}")) {
            var response = postRaw(path, body, "entry-error");
            assertEquals(400, response.statusCode());
            assertError(response, "entry-error");
        }
        String large = "£".repeat(dev.reconcilekit.api.RequestGuard.BODY_LIMIT / 2 + 1);
        for (HttpRequest.BodyPublisher publisher : List.of(HttpRequest.BodyPublishers.ofString(large), HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(large.getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
            var response = client.send(request(path, "import-byte-limit").header("Content-Type", "application/json").POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode()); assertError(response, "import-byte-limit");
        }
        assertEquals(0, count("invoices")); assertEquals(0, count("payments")); assertEquals(0, count("audit_events"));
        var missing = get("/api/v1/import-batches/" + UUID.randomUUID(), "missing-batch");
        assertEquals(404, missing.statusCode()); assertError(missing, "missing-batch");
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void validLeapDateAndExactMoneyBoundsReachDatabase(Kind kind) throws Exception {
        String source = source(kind);
        var min = row(kind, source, "MIN", "0.010");
        min.put(kind == Kind.INVOICES ? "issued_on" : "received_on", "2024-02-29");
        var max = row(kind, source, "MAX", "999999999999.99");
        var result = report(post(kind, payload(source, List.of(min, max)), "exact-boundaries"), 201, "COMMITTED", 2, 2, 0, 0);
        assertEquals(new BigDecimal("0.01"), jdbc.queryForObject("SELECT amount FROM " + table(kind) + " WHERE " + external(kind) + "='MIN'", BigDecimal.class));
        assertEquals(new BigDecimal("999999999999.99"), jdbc.queryForObject("SELECT amount FROM " + table(kind) + " WHERE " + external(kind) + "='MAX'", BigDecimal.class));
        assertImportAudit(result, 2);
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void historicalGregorianGapAndYearBoundsPreserveIsoDatesInSqlQueriesAndReimport(Kind kind) throws Exception {
        String source = source(kind), dateField = kind == Kind.INVOICES ? "issued_on" : "received_on";
        var dates = new ArrayList<String>();
        dates.add("0001-01-01"); dates.add("9999-12-31");
        for (int day = 5; day <= 14; day++) dates.add(String.format("1582-10-%02d", day));
        var rows = new ArrayList<Map<String, Object>>();
        for (int n = 0; n < dates.size(); n++) {
            var row = row(kind, source, "DATE-" + n, "1"); row.put(dateField, dates.get(n)); rows.add(row);
        }
        var body = payload(source, rows);
        report(post(kind, body, "historical-iso-dates"), 201, "COMMITTED", 12, 12, 0, 0);
        for (int n = 0; n < dates.size(); n++) {
            String stored = jdbc.queryForObject("SELECT " + dateField + " FROM " + table(kind) + " WHERE " + external(kind) + "=?",
                    (result, number) -> result.getObject(dateField, java.time.LocalDate.class).toString(), "DATE-" + n);
            assertEquals(dates.get(n), stored);
        }
        var query = get("/api/v1/" + table(kind), "historical-date-query");
        assertEquals(200, query.statusCode());
        for (var record : mapper.readTree(query.body()).get("items")) {
            String id = record.get(kind == Kind.INVOICES ? "externalInvoiceId" : "externalPaymentId").asString();
            assertEquals(dates.get(Integer.parseInt(id.substring(5))), record.get(kind == Kind.INVOICES ? "issuedOn" : "receivedOn").asString());
        }
        report(post(kind, body, "historical-date-reimport"), 201, "COMMITTED", 12, 0, 12, 0);
        assertEquals(12, count(table(kind))); invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t12SameIdentityConcurrentImportsActuallyBlockThenBothCommitWithoutDuplicates(Kind kind) throws Exception {
        overlappingImports(kind, false, false);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t12DifferentSharedContentRollsBackTheLosersEarlierActualInsert(Kind kind) throws Exception {
        overlappingImports(kind, true, false);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void t12RolledBackFirstImporterLetsWaitingIdentityClaimantTakeOver(Kind kind) throws Exception {
        overlappingImports(kind, false, true);
    }

    void overlappingImports(Kind kind, boolean conflicting, boolean rollbackFirst) throws Exception {
        String source = source(kind);
        var firstBody = payload(source, List.of(row(kind, source, "SHARED", "40"), row(kind, source, "Z-ONLY-A", "20")));
        var secondBody = payload(source, List.of(row(kind, source, "SHARED", conflicting ? "41" : "40.000"), row(kind, source, "A-ONLY-B", "30")));
        CountDownLatch firstPaused = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondBeforeShared = new CountDownLatch(1);
        AtomicReference<Context> first = new AtomicReference<>(), second = new AtomicReference<>();
        AtomicReference<Boolean> secondRealInsert = new AtomicReference<>(false);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<HttpResponse<String>> a = null, b = null;
        observer.hook = event -> {
            if (event.stage().equals("started")) {
                assertTrue(event.transactionActive()); assertEquals("read committed", event.isolation());
                if (!first.compareAndSet(null, event.context())) second.set(event.context());
            }
            if (event.stage().equals("row") && event.context().equals(first.get()) && event.row().externalId().equals("SHARED") && event.inserted()) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(kind) + " WHERE id=? AND first_batch_id=?", Integer.class, event.businessId(), first.get().attemptId()));
                firstPaused.countDown(); await(releaseFirst, "release actual shared source-identity insert");
                if (rollbackFirst) throw new IllegalStateException("synthetic rollback after real import insert");
            }
            if (event.stage().equals("row") && event.context().equals(second.get()) && event.row().externalId().equals("A-ONLY-B") && event.inserted()) {
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(kind) + " WHERE id=? AND first_batch_id=?", Integer.class, event.businessId(), second.get().attemptId()));
                secondRealInsert.set(true);
            }
            if (event.stage().equals("beforeRow") && event.context().equals(second.get()) && event.row().externalId().equals("SHARED")) secondBeforeShared.countDown();
        };
        try {
            a = workers.submit(() -> post(kind, firstBody, "overlap-first"));
            await(firstPaused, "first import actually inserted shared unique identity");
            b = workers.submit(() -> post(kind, secondBody, "overlap-second"));
            await(secondBeforeShared, "second import wrote its own earlier row and reached shared identity");
            assertTrue(secondRealInsert.get()); assertNotEquals(first.get().backendPid(), second.get().backendPid());
            awaitBlockedBy(second.get().backendPid(), first.get().backendPid());
            assertEquals(0, count(table(kind)), "Neither transaction's uncommitted rows are visible to observer connection");
            releaseFirst.countDown();
            var firstResult = a.get(10, TimeUnit.SECONDS); var secondResult = b.get(10, TimeUnit.SECONDS);
            JsonNode firstReport = report(firstResult, rollbackFirst ? 500 : 201, rollbackFirst ? "FAILED" : "COMMITTED", 2, rollbackFirst ? 0 : 2, 0, rollbackFirst ? 2 : 0);
            JsonNode secondReport = report(secondResult, conflicting ? 409 : 201, conflicting ? "REJECTED" : "COMMITTED", 2, conflicting ? 0 : rollbackFirst ? 2 : 1, conflicting || rollbackFirst ? 0 : 1, conflicting ? 2 : 0);
            if (rollbackFirst) {
                assertFailedAttemptHasNoBusinessFacts(firstReport, kind); assertSeparateFailureTransaction(first.get().attemptId());
                assertEquals(2, count(table(kind))); assertEquals(1, count("audit_events"));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(kind) + " WHERE " + external(kind) + "='Z-ONLY-A'", Integer.class));
            } else if (conflicting) {
                assertFailedAttemptHasNoBusinessFacts(secondReport, kind); assertSeparateFailureTransaction(second.get().attemptId());
                assertEquals(2, count(table(kind))); assertEquals(1, count("audit_events"));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(kind) + " WHERE " + external(kind) + "='A-ONLY-B'", Integer.class));
            } else {
                assertEquals(3, count(table(kind))); assertEquals(2, count("audit_events"));
                assertEquals(first.get().attemptId(), jdbc.queryForObject("SELECT first_batch_id FROM " + table(kind) + " WHERE " + external(kind) + "='SHARED'", UUID.class));
            }
            assertEquals(new BigDecimal("40.00"), jdbc.queryForObject("SELECT amount FROM " + table(kind) + " WHERE " + external(kind) + "='SHARED'", BigDecimal.class));
            invariantChecks();
            System.out.println("M06 source-identity contention evidence: " + kind + ", backend " + second.get().backendPid() + " blocked by " + first.get().backendPid() + "; second own row actually INSERTed before wait; " + (rollbackFirst ? "first rollback/second takeover" : conflicting ? "second late conflict/whole batch rollback" : "both committed/source dedup"));
        } finally {
            releaseFirst.countDown();
            if (a != null && !a.isDone()) a.cancel(true); if (b != null && !b.isDone()) b.cancel(true);
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    static Stream<Arguments> importFaults() {
        return Stream.of(Arguments.of(Kind.INVOICES, "row"), Arguments.of(Kind.INVOICES, "beforeCommit"), Arguments.of(Kind.PAYMENTS, "row"), Arguments.of(Kind.PAYMENTS, "beforeCommit"));
    }
    @ParameterizedTest @MethodSource("importFaults")
    void t12FirstRealInsertAndFinalSuccessAuditFaultsRollbackBeforeIndependentFailedReport(Kind kind, String faultStage) throws Exception {
        String source = source(kind);
        var body = payload(source, List.of(row(kind, source, "A", "60"), row(kind, source, "B", "40")));
        AtomicReference<UUID> failedAttempt = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals(faultStage) && (!faultStage.equals("row") || event.inserted() && event.insertedCount() == 1)) {
                failedAttempt.set(event.context().attemptId());
                assertEquals(faultStage.equals("row") ? 1 : 2, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(kind) + " WHERE first_batch_id=?", Integer.class, failedAttempt.get()));
                assertEquals(faultStage.equals("row") ? 0 : 1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=?", Integer.class, failedAttempt.get()));
                assertEquals("COMMITTED", jdbc.queryForObject("SELECT outcome FROM import_batches WHERE attempt_id=?", String.class, failedAttempt.get()));
                throw new IllegalStateException("synthetic fault after real import SQL");
            }
            if (event.stage().equals("beforeFailureSave")) {
                assertEquals(0, count(table(kind)), "Business transaction must have rolled back before independent failure save");
                assertEquals(0, count("audit_events"));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE attempt_id=?", Integer.class, event.context().attemptId()));
            }
            if (event.stage().equals("failureSaved")) {
                assertFalse(event.transactionActive());
                assertEquals("FAILED", jdbc.queryForObject("SELECT outcome FROM import_batches WHERE attempt_id=?", String.class, event.context().attemptId()));
            }
        };
        var failure = report(post(kind, body, "actual-import-fault"), 500, "FAILED", 2, 0, 0, 2);
        assertEquals(failedAttempt.get().toString(), failure.get("attemptId").asString());
        assertFailedAttemptHasNoBusinessFacts(failure, kind); assertSeparateFailureTransaction(failedAttempt.get());
        assertEquals(0, count(table(kind))); assertEquals(0, count("audit_events")); assertEquals(1, count("import_batches"));
        observer.reset();
        report(post(kind, body, "fault-cleared-safe-retry"), 201, "COMMITTED", 2, 2, 0, 0);
        assertEquals(2, count(table(kind))); assertEquals(1, count("audit_events"));
        invariantChecks();
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void realUniqueIdentityTimeoutIs55P03AndHttp503LeavesNoHalfRowsBeforeSafeRetry(Kind kind) throws Exception {
        String source = source(kind);
        var body = payload(source, List.of(row(kind, source, "A-NEW", "20"), row(kind, source, "SHARED", "40")));
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Future<?> pending = null;
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            int holder;
            try (var query = held.createStatement(); var rows = query.executeQuery("SELECT pg_backend_pid()")) { assertTrue(rows.next()); holder = rows.getInt(1); }
            UUID holderBatch = UUID.randomUUID();
            try (var insert = held.prepareStatement("INSERT INTO import_batches(attempt_id,source,content_hash,outcome,total,inserted,duplicate,not_imported,request_id) VALUES(?,? ,?,'COMMITTED',1,1,0,0,'external-identity-holder')")) {
                insert.setObject(1, holderBatch); insert.setString(2, source); insert.setString(3, "a".repeat(64)); insert.executeUpdate();
            }
            String insertSql = "INSERT INTO " + table(kind) + "(source," + external(kind) + ",amount,currency," + (kind == Kind.INVOICES ? "issued_on" : "received_on") + ",first_batch_id,source_row) VALUES(?, 'SHARED',40,'GBP','2026-10-01',?,1)";
            try (var insert = held.prepareStatement(insertSql)) { insert.setString(1, source); insert.setObject(2, holderBatch); insert.executeUpdate(); }
            CountDownLatch beforeShared = new CountDownLatch(1);
            AtomicReference<Context> contender = new AtomicReference<>();
            observer.hook = event -> {
                if (event.stage().equals("started")) contender.set(event.context());
                if (event.stage().equals("beforeRow") && event.row().externalId().equals("SHARED")) beforeShared.countDown();
            };
            var parsed = parser.parse(kind, mapper.valueToTree(body), "direct-actual-sqlstate");
            Future<?> direct = workers.submit(() -> atomic.apply(parsed)); pending = direct;
            await(beforeShared, "direct import reached held unique identity"); awaitBlockedBy(contender.get().backendPid(), holder);
            var databaseFailure = assertThrows(ExecutionException.class, () -> direct.get(6, TimeUnit.SECONDS));
            assertEquals("55P03", sqlState(databaseFailure));
            assertEquals(0, count(table(kind))); assertEquals(0, count("audit_events")); assertEquals(0, count("import_batches"));
            observer.reset();
            CountDownLatch httpBeforeShared = new CountDownLatch(1);
            observer.hook = event -> {
                if (event.stage().equals("started")) contender.set(event.context());
                if (event.stage().equals("beforeRow") && event.row().externalId().equals("SHARED")) httpBeforeShared.countDown();
            };
            Future<HttpResponse<String>> http = workers.submit(() -> post(kind, body, "http-unique-timeout")); pending = http;
            await(httpBeforeShared, "HTTP import reached actual held unique identity"); awaitBlockedBy(contender.get().backendPid(), holder);
            var failure = report(http.get(6, TimeUnit.SECONDS), 503, "FAILED", 2, 0, 0, 2);
            assertFailedAttemptHasNoBusinessFacts(failure, kind); assertSeparateFailureTransaction(contender.get().attemptId());
            assertEquals(0, count(table(kind))); assertEquals(0, count("audit_events"));
            held.rollback(); observer.reset();
            report(post(kind, body, "timeout-identity-retry"), 201, "COMMITTED", 2, 2, 0, 0);
            report(post(kind, body, "timeout-identity-reimport"), 201, "COMMITTED", 2, 0, 2, 0);
            assertEquals(2, count(table(kind))); assertEquals(2, count("audit_events"));
            invariantChecks();
            System.out.println("M06 real timeout evidence: " + kind + ", held uncommitted source identity backend " + holder + "; direct actual SQLSTATE55P03 and HTTP503/zero half rows; same identities retry and duplicate import safe");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void terminatedActualBackendReturns503RollsBackBusinessAndPersistsFailureAfterConnectionRecovery(Kind kind) throws Exception {
        String source = source(kind);
        var body = payload(source, List.of(row(kind, source, "A", "60"), row(kind, source, "B", "40")));
        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Context> context = new AtomicReference<>();
        observer.hook = event -> {
            if (event.stage().equals("row") && event.inserted() && event.insertedCount() == 1) {
                context.set(event.context()); inserted.countDown(); await(release, "resume import after backend termination");
            }
        };
        ExecutorService workers = Executors.newSingleThreadExecutor(); Future<HttpResponse<String>> pending = null;
        try {
            pending = workers.submit(() -> post(kind, body, "terminated-import-backend"));
            await(inserted, "first business INSERT before backend termination");
            assertEquals(0, count(table(kind)));
            assertTrue(jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, context.get().backendPid()));
            release.countDown();
            var failure = report(pending.get(8, TimeUnit.SECONDS), 503, "FAILED", 2, 0, 0, 2);
            assertFailedAttemptHasNoBusinessFacts(failure, kind); assertSeparateFailureTransaction(context.get().attemptId());
            assertEquals(0, count(table(kind))); assertEquals(0, count("audit_events"));
            observer.reset();
            report(post(kind, body, "connection-recovered-retry"), 201, "COMMITTED", 2, 2, 0, 0);
            assertEquals(2, count(table(kind))); assertEquals(1, count("audit_events")); invariantChecks();
            System.out.println("M06 backend termination evidence: " + kind + ", actual pg_terminate_backend(" + context.get().backendPid() + ") after first INSERT; HTTP503/whole rollback/independent FAILED report/retry201");
        } finally {
            release.countDown(); if (pending != null && !pending.isDone()) pending.cancel(true);
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void failedReportSavePreservesOriginalErrorWithoutPretendingTheAttemptWasPersisted() throws Exception {
        Kind kind = Kind.INVOICES; String source = source(kind);
        var body = payload(source, List.of(row(kind, source, "A", "60"), row(kind, source, "B", "40")));
        observer.hook = event -> {
            if (event.stage().equals("row") && event.inserted() && event.insertedCount() == 1) {
                assertEquals(1, count("invoices"));
                throw new IllegalStateException("synthetic original business fault after real INSERT");
            }
            if (event.stage().equals("beforeFailureSave")) {
                assertEquals(0, count("invoices")); assertEquals(0, count("audit_events"));
                throw new IllegalStateException("synthetic failure-report persistence fault");
            }
        };
        var response = post(kind, body, "report-persistence-failed");
        assertEquals(500, response.statusCode()); assertError(response, "report-persistence-failed");
        var error = mapper.readTree(response.body());
        assertEquals("INTERNAL_ERROR", error.get("code").asString());
        assertEquals("Internal import operation failed", error.get("message").asString());
        assertFalse(error.get("attemptSaved").asBoolean());
        assertTrue(error.get("outcomeUnknown").asBoolean(), "An unsaved business-attempt failure must explicitly allow an unknown commit outcome");
        var failed = error.get("report");
        assertEquals("FAILED", failed.get("outcome").asString()); assertEquals(2, failed.get("notImported").asInt());
        assertEquals(0, failed.get("inserted").asInt()); assertEquals(0, failed.get("duplicate").asInt());
        assertEquals(404, get("/api/v1/import-batches/" + failed.get("attemptId").asString(), "unsaved-attempt").statusCode());
        assertEquals(0, count("invoices")); assertEquals(0, count("audit_events")); assertEquals(0, count("import_batches"));
        observer.reset(); report(post(kind, body, "persistence-restored-retry"), 201, "COMMITTED", 2, 2, 0, 0);
        invariantChecks();
    }

    @Test void unsavedPrevalidationRejectionKeeps422AndDoesNotClaimAnUnknownBusinessOutcome() throws Exception {
        observer.hook = event -> {
            if (event.stage().equals("beforeFailureSave")) throw new IllegalStateException("synthetic independent failure-save fault");
        };
        var invalid = row(Kind.INVOICES, source(Kind.INVOICES), "INVALID", "0.001");
        var response = post(Kind.INVOICES, payload(source(Kind.INVOICES), List.of(invalid)), "validation-report-unsaved");
        assertEquals(422, response.statusCode()); assertError(response, "validation-report-unsaved");
        var body = mapper.readTree(response.body());
        assertFalse(body.get("attemptSaved").asBoolean()); assertFalse(body.get("outcomeUnknown").asBoolean());
        assertEquals("REJECTED", body.get("report").get("outcome").asString());
        assertTrue(observer.events.stream().noneMatch(event -> event.stage().equals("started")));
        assertEquals(0, count("invoices")); assertEquals(0, count("audit_events")); assertEquals(0, count("import_batches"));
        invariantChecks();
    }

    @Test void paymentRawTextRejectsNulAndLoneSurrogatesWithoutReplacementAndKeepsValidUnicode() throws Exception {
        String source = source(Kind.PAYMENTS);
        var nul = row(Kind.PAYMENTS, source, "P", "60"); nul.put("raw_reference", "bad\u0000reference");
        report(post(Kind.PAYMENTS, payload(source, List.of(nul)), "nul-raw-reference"), 422, "REJECTED", 1, 0, 0, 1);
        String prefix = "{\"source\":\"demo-payments\",\"rows\":[{\"source\":\"demo-payments\",\"external_payment_id\":\"P\",\"amount\":\"60\",\"currency\":\"GBP\",\"received_on\":\"2026-10-01\",\"raw_reference\":\"";
        report(postRaw(importPath(Kind.PAYMENTS), prefix + "\\ud800\"}]}", "lone-surrogate"), 422, "REJECTED", 1, 0, 0, 1);
        var wrongType = row(Kind.PAYMENTS, source, "P", "60"); wrongType.put("raw_reference", 123);
        report(post(Kind.PAYMENTS, payload(source, List.of(wrongType)), "raw-reference-type"), 400, "REJECTED", 1, 0, 0, 1);
        assertEquals(0, count("payments"));
        var unicode = row(Kind.PAYMENTS, source, "PAY-😀", "60"); unicode.put("raw_reference", "真实引用 😀");
        report(post(Kind.PAYMENTS, payload(source, List.of(unicode)), "valid-unicode"), 201, "COMMITTED", 1, 1, 0, 0);
        assertEquals("PAY-😀", jdbc.queryForObject("SELECT external_payment_id FROM payments", String.class));
        assertEquals("真实引用 😀", jdbc.queryForObject("SELECT raw_reference FROM payments", String.class));
        invariantChecks();
    }

    static String source(Kind kind) { return kind == Kind.INVOICES ? "demo-invoices" : "demo-payments"; }
    static String table(Kind kind) { return kind == Kind.INVOICES ? "invoices" : "payments"; }
    static String external(Kind kind) { return kind == Kind.INVOICES ? "external_invoice_id" : "external_payment_id"; }
    static String importPath(Kind kind) { return "/api/v1/imports/" + table(kind); }
    static LinkedHashMap<String, Object> row(Kind kind, String source, String id, String amount) {
        var row = new LinkedHashMap<String, Object>();
        row.put("source", source); row.put(external(kind), id); row.put("amount", amount); row.put("currency", "GBP");
        row.put(kind == Kind.INVOICES ? "issued_on" : "received_on", "2026-10-01");
        return row;
    }
    static Map<String, Object> payload(String source, List<? extends Map<String, Object>> rows) { return Map.of("source", source, "rows", rows); }
    HttpRequest.Builder request(String path, String correlation) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).header("X-Request-ID", correlation);
    }
    HttpResponse<String> post(Kind kind, Map<String, Object> payload, String correlation) throws Exception { return postRaw(importPath(kind), mapper.writeValueAsString(payload), correlation); }
    HttpResponse<String> postRaw(String path, String body, String correlation) throws Exception {
        return client.send(request(path, correlation).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path, String correlation) throws Exception { return client.send(request(path, correlation).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    JsonNode report(HttpResponse<String> response, int status, String outcome, int total, int inserted, int duplicate, int notImported) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        var body = mapper.readTree(response.body());
        JsonNode report = status == 201 ? body : body.get("report");
        assertNotNull(report, "Parseable batches must provide the saved final attempt report: " + response.body());
        assertEquals(outcome, report.get("outcome").asString());
        assertEquals(total, report.get("total").asInt()); assertEquals(inserted, report.get("inserted").asInt());
        assertEquals(duplicate, report.get("duplicate").asInt()); assertEquals(notImported, report.get("notImported").asInt());
        assertEquals(response.headers().firstValue("X-Request-ID").orElseThrow(), report.get("requestId").asString());
        if (status != 201) {
            assertTrue(body.get("attemptSaved").asBoolean()); assertFalse(body.get("outcomeUnknown").asBoolean());
            assertError(response, report.get("requestId").asString());
        }
        var query = get("/api/v1/import-batches/" + report.get("attemptId").asString(), "query-attempt");
        assertEquals(200, query.statusCode()); assertEquals(report, mapper.readTree(query.body()));
        assertEquals(outcome, jdbc.queryForObject("SELECT outcome FROM import_batches WHERE attempt_id=?", String.class, UUID.fromString(report.get("attemptId").asString())));
        return report;
    }
    void assertError(HttpResponse<String> response, String correlation) throws Exception {
        var body = mapper.readTree(response.body());
        assertTrue(body.has("code")); assertTrue(body.has("message")); assertEquals(correlation, body.get("request_id").asString());
        assertFalse(response.body().contains("org.postgresql")); assertFalse(response.body().contains("synthetic")); assertFalse(response.body().contains("SELECT"));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    void assertFailedAttemptHasNoBusinessFacts(JsonNode failed, Kind kind) {
        UUID attempt = UUID.fromString(failed.get("attemptId").asString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(kind) + " WHERE first_batch_id=?", Integer.class, attempt));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=?", Integer.class, attempt));
    }
    void assertImportAudit(JsonNode report, int inserted) throws Exception {
        UUID attempt = UUID.fromString(report.get("attemptId").asString());
        var audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE operation_id=?", attempt);
        assertEquals("IMPORT_COMMITTED", audit.get("event_type")); assertEquals("local", audit.get("actor"));
        assertEquals("import-batch:" + attempt, audit.get("aggregate_id"));
        String detail = (String) audit.get("detail");
        var body = mapper.readTree(detail);
        assertEquals(inserted, body.get("records").size());
        assertEquals(report.get("source").asString(), body.get("source").asString());
        assertEquals(report.get("inserted").asInt(), body.get("inserted").asInt());
        for (JsonNode record : body.get("records")) {
            String table = body.get("kind").asString().equals("INVOICES") ? "invoices" : "payments";
            String identity = table.equals("invoices") ? "external_invoice_id" : "external_payment_id";
            var stored = jdbc.queryForMap("SELECT id," + identity + " AS external_id,amount,source_row FROM " + table + " WHERE id=? AND first_batch_id=?", record.get("id").asLong(), attempt);
            assertEquals(new BigDecimal(record.get("amount").asString()), stored.get("amount"));
            assertEquals(record.get("externalId").asString(), stored.get("external_id"));
            assertEquals(record.get("sourceRow").asInt(), ((Number) stored.get("source_row")).intValue());
        }
        assertFalse(detail.contains("raw_reference"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=?", Integer.class, attempt));
        assertEquals(inserted, jdbc.queryForObject("SELECT inserted FROM import_batches WHERE attempt_id=?", Integer.class, attempt));
    }
    void assertSeparateFailureTransaction(UUID attempt) {
        var events = observer.events.stream().filter(event -> event.context().attemptId().equals(attempt)).toList();
        var business = events.stream().filter(event -> event.stage().equals("started")).findFirst().orElseThrow();
        var saving = events.stream().filter(event -> event.stage().equals("beforeFailureSave")).findFirst().orElseThrow();
        var saved = events.stream().filter(event -> event.stage().equals("failureSaved")).findFirst().orElseThrow();
        assertTrue(business.transactionActive()); assertTrue(saving.transactionActive()); assertFalse(saved.transactionActive());
        assertNotEquals(business.transactionId(), saving.transactionId(), "Failure report requires a new transaction after business exit");
        assertTrue(events.indexOf(saving) > events.indexOf(business));
        assertTrue(events.indexOf(saved) > events.indexOf(saving));
    }
    void invariantChecks() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE (outcome='COMMITTED' AND (total<>inserted+duplicate OR not_imported<>0)) OR (outcome IN('REJECTED','FAILED') AND (inserted<>0 OR duplicate<>0 OR not_imported<>total))", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events e JOIN import_batches b ON b.attempt_id=e.operation_id WHERE b.outcome<>'COMMITTED'", Integer.class));
        for (String table : List.of("invoices", "payments")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " p LEFT JOIN import_batches b ON b.attempt_id=p.first_batch_id WHERE b.attempt_id IS NULL OR b.outcome<>'COMMITTED'", Integer.class));
            String external = table.equals("invoices") ? "external_invoice_id" : "external_payment_id";
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM(SELECT source," + external + " FROM " + table + " GROUP BY source," + external + " HAVING COUNT(*)>1)dupes", Integer.class));
        }
        assertEquals(0, count("allocations")); assertEquals(0, count("idempotency_records"));
    }
    void awaitBlockedBy(int blocked, int blocker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        do {
            if (jdbc.queryForList("SELECT unnest(pg_blocking_pids(?))", Integer.class, blocked).contains(blocker)) {
                assertEquals("Lock", jdbc.queryForObject("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?", String.class, blocked)); return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Actual PostgreSQL source-identity blocker relation was not observed");
    }
    static void await(CountDownLatch latch, String description) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), description); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(description, failure); }
    }
    static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql.getSQLState();
        fail("Actual SQLException with SQLSTATE missing"); return null;
    }
}
