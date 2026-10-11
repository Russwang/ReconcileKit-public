package dev.reconcilekit;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import dev.reconcilekit.invoice.Invoice;
import dev.reconcilekit.payment.Payment;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class DatabaseSchemaTest extends PostgreSqlTestBase {
    private static final String HASH = "a".repeat(64);
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactionManager;
    UUID batch;

    @BeforeEach void isolatedSyntheticLedger() {
        jdbc.execute("TRUNCATE TABLE audit_events, idempotency_records, allocations, payments, invoices, import_batches RESTART IDENTITY");
        batch = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batches(attempt_id,source,content_hash,outcome,total,inserted,duplicate,not_imported,request_id) VALUES(?,'demo',?,'COMMITTED',1,1,0,0,'schema-fixture')", batch, HASH);
    }

    @Test void flywayCreatesDomainTablesAndIndexes() {
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('invoices','payments','allocations','import_batches','idempotency_records','audit_events')", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN ('allocations_payment_idx','allocations_invoice_idx','audit_aggregate_idx')", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT version()", String.class).contains("PostgreSQL 17.11"));
    }

    @Test void differentInvoiceIdentitiesWithEqualAmountsAreLegal() {
        long first = invoice("demo", "INV-1", "60.00", "GBP", batch, 1);
        long second = invoice("demo", "INV-2", "60.00", "GBP", batch, 2);
        assertNotEquals(first, second);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class));
    }

    @Test void invoiceSourceKeyIsCaseSensitiveAndContainsSource() {
        invoice("source-a", "INV-1", "60.00", "GBP", batch, 1);
        invoice("source-b", "INV-1", "60.00", "GBP", batch, 2);
        invoice("source-a", "inv-1", "60.00", "GBP", batch, 3);
        assertSqlState("23505", () -> invoice("source-a", "INV-1", "70.00", "GBP", batch, 4));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class));
    }

    @Test void paymentSourceIdentityIsUniqueButEqualAmountsAreLegal() {
        payment("demo", "PAY-1", "60.00", "GBP", null, null, batch, 1);
        payment("demo", "PAY-2", "60.00", "GBP", null, null, batch, 2);
        payment("other", "PAY-1", "60.00", "GBP", null, null, batch, 3);
        assertSqlState("23505", () -> payment("demo", "PAY-1", "70.00", "GBP", null, null, batch, 4));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test void targetFieldsMayBothBeNullOrBothBePresent() {
        payment("demo", "PAY-1", "60.00", "GBP", null, null, batch, 1);
        payment("demo", "PAY-2", "60.00", "GBP", "demo-invoices", "INV-1", batch, 2);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test void targetHalfPairsAndBlankTargetsAreRejected() {
        assertSqlState("23514", () -> payment("demo", "PAY-1", "60.00", "GBP", "demo-invoices", null, batch, 1));
        assertSqlState("23514", () -> payment("demo", "PAY-2", "60.00", "GBP", null, "INV-1", batch, 2));
        assertSqlState("23514", () -> payment("demo", "PAY-3", "60.00", "GBP", "", "INV-1", batch, 3));
        assertSqlState("23514", () -> payment("demo", "PAY-4", "60.00", "GBP", "demo-invoices", " INV-1 ", batch, 4));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"0,23514", "-1,23514", "1000000000000.00,23514", "NaN,23514", "Infinity,22003"})
    void databaseRejectsInvalidInvoiceAndPaymentAmounts(String amount, String expectedSqlState) {
        // Constrained numeric rejects Infinity before the CHECK constraint is evaluated.
        assertSqlState(expectedSqlState, () -> invoice("demo", "INV-X", amount, "GBP", batch, 1));
        assertSqlState(expectedSqlState, () -> payment("demo", "PAY-X", amount, "GBP", null, null, batch, 1));
    }

    @Test void exactUpperAndSmallestAmountsAreStored() {
        invoice("demo", "INV-MAX", "999999999999.99", "GBP", batch, 1);
        payment("demo", "PAY-MIN", "0.01", "GBP", null, null, batch, 1);
        assertEquals(new BigDecimal("999999999999.99"), jdbc.queryForObject("SELECT amount FROM invoices", BigDecimal.class));
        assertEquals(new BigDecimal("0.01"), jdbc.queryForObject("SELECT amount FROM payments", BigDecimal.class));
    }

    @Test void numericStorageRoundsSoApiValidationRemainsRequired() {
        invoice("demo", "INV-X", "0.011", "GBP", batch, 1);
        assertEquals(new BigDecimal("0.01"), jdbc.queryForObject("SELECT amount FROM invoices", BigDecimal.class));
    }

    @Test void currencyIdentityAndRequiredFieldsAreConstrained() {
        assertSqlState("23514", () -> invoice("demo", "INV-X", "60.00", "EUR", batch, 1));
        assertSqlState("23514", () -> payment("demo", "PAY-X", "60.00", "gbp", null, null, batch, 1));
        assertSqlState("23514", () -> invoice("", "INV-X", "60.00", "GBP", batch, 1));
        assertSqlState("23514", () -> invoice("demo", " INV-X ", "60.00", "GBP", batch, 1));
        assertSqlState("23502", () -> invoice("demo", null, "60.00", "GBP", batch, 1));
        assertSqlState("22001", () -> invoice("demo", "X".repeat(101), "60.00", "GBP", batch, 1));
        assertSqlState("23514", () -> invoice("demo", "INV-X", "60.00", "GBP", batch, 0));
        assertSqlState("23502", () -> jdbc.update("INSERT INTO invoices(source,external_invoice_id,amount,currency,first_batch_id,source_row) VALUES('demo','INV-X',1,'GBP',?,1)", batch));
    }

    @Test void importedRowsRequireExistingFirstBatch() {
        assertSqlState("23503", () -> invoice("demo", "INV-X", "60.00", "GBP", UUID.randomUUID(), 1));
        assertSqlState("23503", () -> payment("demo", "PAY-X", "60.00", "GBP", null, null, UUID.randomUUID(), 1));
    }

    @Test void allocationsHaveParentForeignKeysAndAllowSamePairAppend() {
        long invoice = invoice("demo", "INV-X", "100.00", "GBP", batch, 1);
        long payment = payment("demo", "PAY-X", "100.00", "GBP", null, null, batch, 1);
        allocation(payment, invoice, "30.00");
        allocation(payment, invoice, "20.00");
        assertEquals(new BigDecimal("50.00"), jdbc.queryForObject("SELECT SUM(amount) FROM allocations", BigDecimal.class));
        assertSqlState("23503", () -> allocation(9999, invoice, "1.00"));
        assertSqlState("23503", () -> allocation(payment, 9999, "1.00"));
        assertSqlState("23514", () -> allocation(payment, invoice, "0"));
        assertSqlState("23514", () -> allocation(payment, invoice, "1000000000000.00"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM allocations", Integer.class));
    }

    @Test void idempotencyUsesOperationTypeScopeAndPreservesResponseText() {
        UUID operation = UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_records(operation_type,key,request_hash,operation_id) VALUES('MANUAL_ALLOCATION','same-key',?,?)", HASH, operation);
        String body = "{ \"amount\": \"60.00\", \"operation\": \"saved\" }";
        jdbc.update("UPDATE idempotency_records SET response_status=201,response_body=? WHERE operation_type='MANUAL_ALLOCATION' AND key='same-key'", body);
        assertEquals(body, jdbc.queryForObject("SELECT response_body FROM idempotency_records", String.class));
        assertSqlState("23505", () -> jdbc.update("INSERT INTO idempotency_records(operation_type,key,request_hash,operation_id) VALUES('MANUAL_ALLOCATION','same-key',?,?)", HASH, UUID.randomUUID()));
        jdbc.update("INSERT INTO idempotency_records(operation_type,key,request_hash,operation_id) VALUES('AUTO_ALLOCATION','same-key',?,?)", HASH, UUID.randomUUID());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
        assertSqlState("23514", () -> jdbc.update("UPDATE idempotency_records SET response_status=201 WHERE operation_type='AUTO_ALLOCATION'"));
        assertSqlState("23514", () -> jdbc.update("UPDATE idempotency_records SET response_status=500,response_body='{}' WHERE operation_type='AUTO_ALLOCATION'"));
    }

    @Test void batchReportsOnlyFinalOutcomesAndConsistentCounts() {
        assertSqlState("23514", () -> jdbc.update("UPDATE import_batches SET outcome='RUNNING' WHERE attempt_id=?", batch));
        assertSqlState("23514", () -> jdbc.update("UPDATE import_batches SET inserted=0 WHERE attempt_id=?", batch));
        assertSqlState("23514", () -> jdbc.update("UPDATE import_batches SET outcome='FAILED' WHERE attempt_id=?", batch));
        jdbc.update("UPDATE import_batches SET outcome='FAILED', inserted=0, duplicate=0, not_imported=total WHERE attempt_id=?", batch);
        assertEquals("FAILED", jdbc.queryForObject("SELECT outcome FROM import_batches WHERE attempt_id=?", String.class, batch));
    }

    @Test void auditActorsAndAllocationOriginsAreConstrained() {
        assertSqlState("23514", () -> jdbc.update("INSERT INTO audit_events(operation_id,actor,event_type,aggregate_id,detail) VALUES(?,'anonymous','ALLOCATED','1','{}')", UUID.randomUUID()));
        jdbc.update("INSERT INTO audit_events(operation_id,actor,event_type,aggregate_id,detail) VALUES(?,'local','ALLOCATED','1','{}')", UUID.randomUUID());
        long invoice = invoice("demo", "INV-X", "100", "GBP", batch, 1);
        long payment = payment("demo", "PAY-X", "100", "GBP", null, null, batch, 1);
        assertSqlState("23514", () -> jdbc.update("INSERT INTO allocations(operation_id,payment_id,invoice_id,amount,origin) VALUES(?,?,?,1,'UNKNOWN')", UUID.randomUUID(), payment, invoice));
    }

    @Test void jpaEntityMappingRoundTripsInvoiceAndPaymentWithoutStoredBalances() {
        var tx = new TransactionTemplate(transactionManager);
        Long invoiceId = tx.execute(status -> {
            Invoice entity = new Invoice("demo", "INV-JPA", new BigDecimal("60.00"), "GBP", LocalDate.of(2026, 10, 8), batch, 1);
            entities.persist(entity);
            entities.flush();
            return entity.getId();
        });
        Long paymentId = tx.execute(status -> {
            Payment entity = new Payment("demo", "PAY-JPA", new BigDecimal("60.00"), "GBP", LocalDate.of(2026, 10, 8), "INV-JPA", "demo", "INV-JPA", batch, 1);
            entities.persist(entity);
            entities.flush();
            return entity.getId();
        });
        Invoice invoice = entities.find(Invoice.class, invoiceId);
        Payment payment = entities.find(Payment.class, paymentId);
        assertEquals("INV-JPA", invoice.getExternalInvoiceId());
        assertEquals(new BigDecimal("60.00"), invoice.getAmount());
        assertEquals(batch, invoice.getFirstBatchId());
        assertNotNull(invoice.getCreatedAt());
        assertEquals("INV-JPA", payment.getRawReference());
        assertEquals("demo", payment.getTargetInvoiceSource());
        assertEquals(LocalDate.of(2026, 10, 8), payment.getReceivedOn());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name IN ('invoices','payments') AND column_name IN ('balance','remaining','status')", Integer.class));
    }

    private long invoice(String source, String externalId, String amount, String currency, UUID batchId, int row) {
        return jdbc.queryForObject("INSERT INTO invoices(source,external_invoice_id,amount,currency,issued_on,first_batch_id,source_row) VALUES(?,?,?::numeric,?,DATE '2026-10-08',?,?) RETURNING id", Long.class, source, externalId, amount, currency, batchId, row);
    }

    private long payment(String source, String externalId, String amount, String currency, String targetSource, String targetId, UUID batchId, int row) {
        return jdbc.queryForObject("INSERT INTO payments(source,external_payment_id,amount,currency,received_on,target_invoice_source,target_external_invoice_id,first_batch_id,source_row) VALUES(?,?,?::numeric,?,DATE '2026-10-08',?,?,?,?) RETURNING id", Long.class, source, externalId, amount, currency, targetSource, targetId, batchId, row);
    }

    private void allocation(long paymentId, long invoiceId, String amount) {
        jdbc.update("INSERT INTO allocations(operation_id,payment_id,invoice_id,amount,origin) VALUES(?,?,?,?::numeric,'MANUAL')", UUID.randomUUID(), paymentId, invoiceId, amount);
    }

    private void assertSqlState(String expected, Runnable write) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, write::run);
        Throwable cause = error;
        while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
        assertNotNull(cause, "Database exception must preserve SQLSTATE");
        assertEquals(expected, ((SQLException) cause).getSQLState());
    }
}
