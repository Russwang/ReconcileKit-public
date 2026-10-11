package dev.reconcilekit.importbatch;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class FailureAttemptService {
    public record Saved(ImportReport report, ImportObserver.Context context) {}
    private final JdbcTemplate jdbc;
    private final ImportBatchQueries queries;
    private final ImportObserver observer;
    private final ObjectMapper mapper;
    public FailureAttemptService(JdbcTemplate jdbc, ImportBatchQueries queries, ImportObserver observer, ObjectMapper mapper) {
        this.jdbc = jdbc; this.queries = queries; this.observer = observer; this.mapper = mapper;
    }
    @Transactional(transactionManager = "ledgerTransactions", propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Saved save(ImportAttempt attempt, String outcome, String summary, List<ImportReport.RowError> errors) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        var context = new ImportObserver.Context(attempt.attemptId(), attempt.kind().name(), jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        observer.beforeFailureAttemptSave(context);
        // A connection failure during commit may leave its outcome unknown. Never overwrite a
        // COMMITTED attempt if the original commit actually succeeded before the connection failed.
        jdbc.update("INSERT INTO import_batches(attempt_id,source,content_hash,outcome,total,inserted,duplicate,not_imported,row_errors,error_summary,request_id) VALUES(?,?,?,?,?,0,0,?,?,?,?) ON CONFLICT(attempt_id) DO NOTHING",
                attempt.attemptId(), attempt.source(), attempt.contentHash(), outcome, attempt.total(), attempt.total(), mapper.writeValueAsString(errors), summary, attempt.requestId());
        return new Saved(queries.report(attempt.attemptId()), context);
    }
}
