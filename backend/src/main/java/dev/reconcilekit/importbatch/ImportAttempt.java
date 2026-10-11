package dev.reconcilekit.importbatch;

import java.util.UUID;

record ImportAttempt(UUID attemptId, ImportCommand.Kind kind, String source, int total,
        String contentHash, String requestId) {
    static ImportAttempt of(ImportCommand command) {
        return new ImportAttempt(command.attemptId(), command.kind(), command.source(), command.total(), command.contentHash(), command.requestId());
    }
}
