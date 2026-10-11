package dev.reconcilekit.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Structural transaction observation only; production has no fault controls or request payloads. */
public interface TransactionObserver {
    record Context(UUID operationId, String operationType, int backendPid) {}
    enum Parent { PAYMENT, INVOICE }
    record InvoiceBalance(long invoiceId, BigDecimal remaining) {}

    default void transactionStarted(Context context) {}
    default void beforeIdempotencyClaim(Context context) {}
    default void afterIdempotencyClaim(Context context, boolean claimed) {}
    default void beforeParentLock(Context context, Parent parent, long id) {}
    default void afterParentLock(Context context, Parent parent, long id) {}
    default void afterBalancesRead(Context context, long paymentId, BigDecimal paymentRemaining, List<InvoiceBalance> invoices) {}
    default void afterAllocationInsert(Context context, long allocationId, int insertedCount) {}
    default void beforeSuccessfulCommit(Context context) {}
}
