package dev.reconcilekit;

import dev.reconcilekit.reconciliation.ReconciliationRunObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestConfiguration
public class ReconciliationTestConfiguration {
    @Bean @Primary ControlledRunObserver controlledRunObserver() { return new ControlledRunObserver(); }
    static class ControlledRunObserver implements ReconciliationRunObserver {
        record Event(UUID runId, long paymentId, int processed, boolean transactionActive) {}
        final List<Event> events = new CopyOnWriteArrayList<>();
        volatile Consumer<Event> hook = ignored -> {};
        void reset() { hook = ignored -> {}; events.clear(); }
        @Override public void afterPaymentProcessed(UUID runId, long paymentId, int processedCount) {
            var event = new Event(runId, paymentId, processedCount, TransactionSynchronizationManager.isActualTransactionActive());
            events.add(event); hook.accept(event);
        }
    }
}
