package dev.reconcilekit.reconciliation;

import dev.reconcilekit.allocation.AllocationService;
import dev.reconcilekit.api.ApiException;
import dev.reconcilekit.api.DatabaseFailures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One independently committed payment at a time; a run has no enclosing transaction. */
@Service
public class ReconciliationRunService {
    public record PaymentResult(long paymentId, String status, String reason, boolean replayed,
            UUID operationId, List<Long> allocationIds, String code, String message, boolean retryable) {
        public PaymentResult { allocationIds = List.copyOf(allocationIds); }
    }
    public record RunReport(UUID runId, int total, int applied, int skipped, int review, int failed, List<PaymentResult> items) {
        public RunReport { items = List.copyOf(items); }
    }
    private final AllocationService allocations;
    private final ReconciliationRunObserver observer;
    public ReconciliationRunService(AllocationService allocations, ReconciliationRunObserver observer) { this.allocations = allocations; this.observer = observer; }
    public RunReport run(List<Long> ids) {
        List<Long> normalized = ReconciliationRunCommand.validate(ids);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Reconciliation run must execute outside a transaction");
        UUID runId = UUID.randomUUID();
        var items = new ArrayList<PaymentResult>(normalized.size());
        int applied = 0, skipped = 0, review = 0, failed = 0;
        for (long paymentId : normalized) {
            PaymentResult result;
            try {
                var response = allocations.allocateAutomatically(paymentId);
                result = new PaymentResult(paymentId, response.replayed() ? "SKIPPED" : "APPLIED", null, response.replayed(),
                        response.applied().operationId(), response.applied().allocationIds(), null, null, false);
            } catch (AutomaticNotApplied noWrite) {
                result = new PaymentResult(paymentId, noWrite.outcome().name(), noWrite.reason(), false, null, List.of(), null, null, false);
            } catch (ApiException error) {
                result = new PaymentResult(paymentId, "FAILED", null, false, null, List.of(), error.code(), error.getMessage(), error.status() == 503);
            } catch (Exception error) {
                boolean retryable = DatabaseFailures.retryable(error);
                result = new PaymentResult(paymentId, "FAILED", null, false, null, List.of(), retryable ? "RETRYABLE_DATABASE_ERROR" : "INTERNAL_ERROR",
                        retryable ? "Database temporarily unavailable; rerun the same payment IDs" : "Internal reconciliation operation failed", retryable);
            }
            items.add(result);
            switch (result.status()) {
                case "APPLIED" -> applied++;
                case "SKIPPED" -> skipped++;
                case "REVIEW" -> review++;
                case "FAILED" -> failed++;
                default -> throw new IllegalStateException("Unknown reconciliation outcome");
            }
            // Deliberately outside the per-payment catch: interruption after a real commit can
            // stop this run without cancelling already committed payments or future safe reruns.
            observer.afterPaymentProcessed(runId, paymentId, items.size());
        }
        return new RunReport(runId, normalized.size(), applied, skipped, review, failed, items);
    }
}
