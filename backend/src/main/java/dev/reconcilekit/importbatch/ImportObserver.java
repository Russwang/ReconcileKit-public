package dev.reconcilekit.importbatch;

import java.util.UUID;

/** Structural callbacks only. Production exposes no switches, request data or fault endpoint. */
public interface ImportObserver {
    record Context(UUID attemptId, String importType, int backendPid) {}
    record RowIdentity(String source, String externalId, int sourceRow) {}
    default void transactionStarted(Context context) {}
    default void beforeRowInsert(Context context, RowIdentity row) {}
    default void afterRowInsert(Context context, RowIdentity row, long businessId, boolean inserted, int insertedCount) {}
    default void beforeSuccessfulCommit(Context context) {}
    default void beforeFailureAttemptSave(Context context) {}
    /** Called outside the transaction, after the failure-save proxy has committed. */
    default void failureAttemptSaved(Context context, ImportReport report) {}
}
