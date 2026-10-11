package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.ApiException;
import java.util.List;

/** Internal rejection leaves the transaction before the orchestrator records a final attempt. */
final class ImportRejection extends ApiException {
    final ImportAttempt attempt;
    final List<ImportReport.RowError> rowErrors;
    ImportRejection(ImportAttempt attempt, int status, String code, String message, List<ImportReport.RowError> rowErrors) {
        super(status, code, message); this.attempt = attempt; this.rowErrors = List.copyOf(rowErrors);
    }
}
