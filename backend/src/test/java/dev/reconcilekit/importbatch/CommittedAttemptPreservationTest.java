package dev.reconcilekit.importbatch;

import static org.junit.jupiter.api.Assertions.*;
import dev.reconcilekit.PostgreSqlTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit existing-COMMITTED branch regression; this does not simulate a lost commit acknowledgement. */
@SpringBootTest
class CommittedAttemptPreservationTest extends PostgreSqlTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportOrchestrator importer;
    @Autowired FailureAttemptService failures;
    @Autowired ObjectMapper mapper;

    @Test void failureSaverCannotOverwriteTheAlreadyCommittedAttemptWithTheSameUuid() throws Exception {
        jdbc.execute("TRUNCATE audit_events,idempotency_records,allocations,payments,invoices,import_batches RESTART IDENTITY");
        ImportReport committed = importer.importBatch(ImportCommand.Kind.INVOICES,
                mapper.readTree("{\"source\":\"demo-invoices\",\"rows\":[{\"source\":\"demo-invoices\",\"external_invoice_id\":\"COMMITTED\",\"amount\":\"60\",\"currency\":\"GBP\",\"issued_on\":\"2026-10-01\"}]}"), "original-committed-request");
        assertEquals("COMMITTED", committed.outcome()); assertEquals(1, committed.inserted());
        var businessBefore = jdbc.queryForList("SELECT * FROM invoices ORDER BY id");
        var auditBefore = jdbc.queryForList("SELECT * FROM audit_events ORDER BY id");
        var batchBefore = jdbc.queryForList("SELECT * FROM import_batches ORDER BY attempt_id");
        var sameAttempt = new ImportAttempt(committed.attemptId(), ImportCommand.Kind.INVOICES, committed.source(), committed.total(), committed.contentHash(), "later-failure-save");
        var saved = failures.save(sameAttempt, "FAILED", "synthetic later save request", List.of(new ImportReport.RowError(1, "SYNTHETIC", "later failure request")));
        assertEquals(committed, saved.report());
        assertEquals("COMMITTED", saved.report().outcome()); assertEquals(1, saved.report().inserted()); assertEquals(0, saved.report().notImported());
        assertEquals(businessBefore, jdbc.queryForList("SELECT * FROM invoices ORDER BY id"));
        assertEquals(auditBefore, jdbc.queryForList("SELECT * FROM audit_events ORDER BY id"));
        assertEquals(batchBefore, jdbc.queryForList("SELECT * FROM import_batches ORDER BY attempt_id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE operation_id=? AND event_type='IMPORT_COMMITTED'", Integer.class, committed.attemptId()));
    }
}
