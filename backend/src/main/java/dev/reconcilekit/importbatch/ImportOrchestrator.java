package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.DatabaseFailures;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** Deliberately nontransactional: every failed business proxy call has already exited here. */
@Service
public class ImportOrchestrator {
    private final ImportParser parser;
    private final AtomicImportService atomic;
    private final FailureAttemptService failures;
    private final ImportObserver observer;
    public ImportOrchestrator(ImportParser parser, AtomicImportService atomic, FailureAttemptService failures, ImportObserver observer) {
        this.parser = parser; this.atomic = atomic; this.failures = failures; this.observer = observer;
    }
    public ImportReport importBatch(ImportCommand.Kind kind, JsonNode body, String requestId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Import orchestration must run outside a transaction");
        ImportCommand command;
        try { command = parser.parse(kind, body, requestId); }
        catch (ImportRejection error) { throw finishFailure(error.attempt, error.status(), error.code(), error.getMessage(), "REJECTED", error.rowErrors, false); }
        try { return atomic.apply(command); }
        catch (ImportRejection error) { throw finishFailure(error.attempt, error.status(), error.code(), error.getMessage(), "REJECTED", error.rowErrors, true); }
        catch (Exception error) {
            boolean retryable = DatabaseFailures.retryable(error);
            int status = retryable ? 503 : 500;
            String code = retryable ? "RETRYABLE_DATABASE_ERROR" : "INTERNAL_ERROR";
            String message = retryable ? "Database temporarily unavailable; retry with the same source identities and content" : "Internal import operation failed";
            LoggerFactory.getLogger(ImportOrchestrator.class).warn("Import transaction failed: request_id={}", requestId);
            throw finishFailure(ImportAttempt.of(command), status, code, message, "FAILED", List.of(), true);
        }
    }
    private ImportFailure finishFailure(ImportAttempt attempt, int status, String code, String message, String outcome, List<ImportReport.RowError> errors, boolean businessAttempted) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Failure attempt cannot be recorded before business transaction exit");
        ImportReport report = new ImportReport(attempt.attemptId(), attempt.source(), attempt.contentHash(), outcome, attempt.total(), 0, 0, attempt.total(), errors, message, attempt.requestId(), null);
        boolean saved = false;
        try {
            FailureAttemptService.Saved result = failures.save(attempt, outcome, message, errors);
            report = result.report(); saved = true;
            observer.failureAttemptSaved(result.context(), report);
        } catch (Exception ignored) {
            LoggerFactory.getLogger(ImportOrchestrator.class).warn("Import failure report could not be saved: request_id={}", attempt.requestId());
        }
        // Zero failure counts describe this report, not a proven commit outcome after lost DB
        // acknowledgement. A stored COMMITTED attempt, if present, remains authoritative.
        return new ImportFailure(status, code, message, report, saved, businessAttempted && !saved);
    }
}
