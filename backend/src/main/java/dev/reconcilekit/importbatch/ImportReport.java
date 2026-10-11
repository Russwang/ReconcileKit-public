package dev.reconcilekit.importbatch;

import java.util.List;
import java.util.UUID;

public record ImportReport(UUID attemptId, String source, String contentHash, String outcome,
        int total, int inserted, int duplicate, int notImported, List<RowError> rowErrors,
        String errorSummary, String requestId, String createdAt) {
    public ImportReport { rowErrors = List.copyOf(rowErrors); }
    /** Rows are one-based; row zero identifies a batch-level validation problem. */
    public record RowError(int row, String code, String message) {}
}
