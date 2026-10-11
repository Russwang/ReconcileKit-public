package dev.reconcilekit;

import dev.reconcilekit.importbatch.ImportObserver;
import dev.reconcilekit.importbatch.ImportReport;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic test controls only; callbacks observe actual PostgreSQL transaction state. */
public class ControlledImportObserver implements ImportObserver {
    public record Event(String stage, Context context, RowIdentity row, Long businessId,
            boolean inserted, int insertedCount, ImportReport report, boolean transactionActive,
            String transactionId, String isolation) {}
    private final JdbcTemplate jdbc;
    private record Snapshot(String id, String isolation) {}
    private final Map<UUID, Snapshot> transactions = new ConcurrentHashMap<>();
    final List<Event> events = new CopyOnWriteArrayList<>();
    volatile Consumer<Event> hook = ignored -> {};
    public ControlledImportObserver(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void reset() { hook = ignored -> {}; events.clear(); transactions.clear(); }
    private void emit(String stage, Context context, RowIdentity row, Long businessId, boolean inserted, int count, ImportReport report) {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        if (active && (stage.equals("started") || stage.equals("beforeFailureSave"))) {
            transactions.put(context.attemptId(), new Snapshot(jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class),
                    jdbc.queryForObject("SHOW transaction_isolation", String.class)));
        }
        Snapshot snapshot = active ? transactions.get(context.attemptId()) : null;
        String transaction = snapshot == null ? null : snapshot.id();
        String isolation = snapshot == null ? null : snapshot.isolation();
        var event = new Event(stage, context, row, businessId, inserted, count, report, active, transaction, isolation);
        events.add(event); hook.accept(event);
    }
    @Override public void transactionStarted(Context context) { emit("started", context, null, null, false, 0, null); }
    @Override public void beforeRowInsert(Context context, RowIdentity row) { emit("beforeRow", context, row, null, false, 0, null); }
    @Override public void afterRowInsert(Context context, RowIdentity row, long id, boolean inserted, int count) { emit("row", context, row, id, inserted, count, null); }
    @Override public void beforeSuccessfulCommit(Context context) { emit("beforeCommit", context, null, null, false, 0, null); }
    @Override public void beforeFailureAttemptSave(Context context) { emit("beforeFailureSave", context, null, null, false, 0, null); }
    @Override public void failureAttemptSaved(Context context, ImportReport report) { emit("failureSaved", context, null, null, false, 0, report); }
}
