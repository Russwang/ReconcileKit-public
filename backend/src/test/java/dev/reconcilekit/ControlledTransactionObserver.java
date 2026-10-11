package dev.reconcilekit;

import dev.reconcilekit.transaction.TransactionObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only coordination; every callback still runs inside the real service transaction. */
public class ControlledTransactionObserver implements TransactionObserver {
    public record Event(String stage, Context context, Parent parent, Long parentId,
            BigDecimal paymentRemaining, List<InvoiceBalance> invoices, Long allocationId,
            int insertedCount, String isolation) {}
    private final JdbcTemplate jdbc;
    final List<Event> events = new CopyOnWriteArrayList<>();
    volatile Consumer<Event> hook = ignored -> {};
    public ControlledTransactionObserver(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void reset() { hook = ignored -> {}; events.clear(); }
    private void emit(Event event) { events.add(event); hook.accept(event); }
    @Override public void transactionStarted(Context context) {
        emit(new Event("started", context, null, null, null, List.of(), null, 0,
                jdbc.queryForObject("SHOW transaction_isolation", String.class)));
    }
    @Override public void beforeParentLock(Context context, Parent parent, long id) {
        emit(new Event("beforeLock", context, parent, id, null, List.of(), null, 0, null));
    }
    @Override public void beforeIdempotencyClaim(Context context) {
        emit(new Event("beforeClaim", context, null, null, null, List.of(), null, 0, null));
    }
    @Override public void afterIdempotencyClaim(Context context, boolean claimed) {
        emit(new Event(claimed ? "claimed" : "replayClaim", context, null, null, null, List.of(), null, 0, null));
    }
    @Override public void afterParentLock(Context context, Parent parent, long id) {
        emit(new Event("afterLock", context, parent, id, null, List.of(), null, 0, null));
    }
    @Override public void afterBalancesRead(Context context, long paymentId, BigDecimal remaining, List<InvoiceBalance> invoices) {
        emit(new Event("balances", context, Parent.PAYMENT, paymentId, remaining, List.copyOf(invoices), null, 0, null));
    }
    @Override public void afterAllocationInsert(Context context, long id, int count) {
        emit(new Event("inserted", context, null, null, null, List.of(), id, count, null));
    }
    @Override public void beforeSuccessfulCommit(Context context) {
        emit(new Event("beforeCommit", context, null, null, null, List.of(), null, 0, null));
    }
}
