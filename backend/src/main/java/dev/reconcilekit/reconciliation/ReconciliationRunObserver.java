package dev.reconcilekit.reconciliation;

import java.util.UUID;

/** Structural observation after each per-payment proxy exits; no production fault controls. */
public interface ReconciliationRunObserver {
    default void afterPaymentProcessed(UUID runId, long paymentId, int processedCount) {}
}
