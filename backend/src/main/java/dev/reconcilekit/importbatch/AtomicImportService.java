package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Each request is one READ COMMITTED transaction; immutable rows are never updated. */
@Service
public class AtomicImportService {
    private record StoredRow(long id, ImportCommand.Row row) {}
    private record CreatedRecord(long id, String externalId, String amount, int sourceRow) {}
    private record AuditDetail(String kind, String source, int total, int inserted, int duplicate, List<CreatedRecord> records) {}
    private final JdbcTemplate jdbc;
    private final ImportBatchQueries queries;
    private final ImportObserver observer;
    private final ObjectMapper mapper;
    private final ImportParser parser;
    public AtomicImportService(JdbcTemplate jdbc, ImportBatchQueries queries, ImportObserver observer, ObjectMapper mapper, ImportParser parser) {
        this.jdbc = jdbc; this.queries = queries; this.observer = observer; this.mapper = mapper; this.parser = parser;
    }
    @Transactional(transactionManager = "ledgerTransactions", isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ImportReport apply(ImportCommand command) {
        command = parser.canonicalize(command);
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        var context = new ImportObserver.Context(command.attemptId(), command.kind().name(), jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        observer.transactionStarted(context);
        // This zero-count row is invisible until commit. It supplies the first_batch FK while
        // all final counts, business rows and the success audit are built in this transaction.
        jdbc.update("INSERT INTO import_batches(attempt_id,source,content_hash,outcome,total,inserted,duplicate,not_imported,request_id) VALUES(?,?,?,'COMMITTED',0,0,0,0,?)",
                command.attemptId(), command.source(), command.contentHash(), command.requestId());
        var unique = new LinkedHashMap<String, ImportCommand.Row>();
        for (var row : command.rows()) unique.putIfAbsent(row.externalId(), row);
        var ordered = unique.values().stream().sorted(Comparator.comparing(ImportCommand.Row::source).thenComparing(ImportCommand.Row::externalId)).toList();
        int inserted = 0;
        var created = new ArrayList<CreatedRecord>();
        for (var row : ordered) {
            var identity = new ImportObserver.RowIdentity(row.source(), row.externalId(), row.sourceRow());
            observer.beforeRowInsert(context, identity);
            List<Long> ids = insert(command, row);
            boolean fresh = !ids.isEmpty();
            long id;
            if (fresh) {
                id = ids.getFirst(); inserted++;
                created.add(new CreatedRecord(id, row.externalId(), Money.format(row.amount()), row.sourceRow()));
            } else {
                // ON CONFLICT may wait on an invisible competitor. This later statement takes
                // a new READ COMMITTED snapshot; no aborted uniqueness-error transaction is reused.
                StoredRow stored = existing(command.kind(), row.source(), row.externalId());
                if (!row.sameBusiness(stored.row())) throw new ImportRejection(ImportAttempt.of(command), 409, "BUSINESS_CONFLICT", "Existing source identity has different business content",
                        List.of(new ImportReport.RowError(row.sourceRow(), "BUSINESS_CONFLICT", "Existing source identity has different business content")));
                id = stored.id();
            }
            observer.afterRowInsert(context, identity, id, fresh, inserted);
        }
        int duplicate = command.total() - inserted;
        jdbc.update("UPDATE import_batches SET total=?,inserted=?,duplicate=? WHERE attempt_id=?", command.total(), inserted, duplicate, command.attemptId());
        jdbc.update("INSERT INTO audit_events(operation_id,actor,event_type,aggregate_id,detail) VALUES(?,'local','IMPORT_COMMITTED',?,?)",
                command.attemptId(), "import-batch:" + command.attemptId(), mapper.writeValueAsString(new AuditDetail(command.kind().name(), command.source(), command.total(), inserted, duplicate, List.copyOf(created))));
        ImportReport report = queries.report(command.attemptId());
        observer.beforeSuccessfulCommit(context);
        return report;
    }
    private List<Long> insert(ImportCommand command, ImportCommand.Row row) {
        if (command.kind() == ImportCommand.Kind.INVOICES) {
            return jdbc.queryForList("INSERT INTO invoices(source,external_invoice_id,amount,currency,issued_on,first_batch_id,source_row) VALUES(?,?,?,?,?,?,?) ON CONFLICT(source,external_invoice_id) DO NOTHING RETURNING id", Long.class,
                    row.source(), row.externalId(), row.amount(), row.currency(), row.occurredOn(), command.attemptId(), row.sourceRow());
        }
        return jdbc.queryForList("INSERT INTO payments(source,external_payment_id,amount,currency,received_on,raw_reference,target_invoice_source,target_external_invoice_id,first_batch_id,source_row) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source,external_payment_id) DO NOTHING RETURNING id", Long.class,
                row.source(), row.externalId(), row.amount(), row.currency(), row.occurredOn(), row.rawReference(), row.targetInvoiceSource(), row.targetExternalInvoiceId(), command.attemptId(), row.sourceRow());
    }
    private StoredRow existing(ImportCommand.Kind kind, String source, String externalId) {
        if (kind == ImportCommand.Kind.INVOICES) {
            return jdbc.queryForObject("SELECT id,source,external_invoice_id,amount,currency,issued_on FROM invoices WHERE source=? AND external_invoice_id=?",
                    (row, number) -> new StoredRow(row.getLong("id"), new ImportCommand.Row(0, row.getString("source"), row.getString("external_invoice_id"), row.getBigDecimal("amount"), row.getString("currency"), row.getObject("issued_on", LocalDate.class), null, null, null)), source, externalId);
        }
        return jdbc.queryForObject("SELECT id,source,external_payment_id,amount,currency,received_on,raw_reference,target_invoice_source,target_external_invoice_id FROM payments WHERE source=? AND external_payment_id=?",
                (row, number) -> new StoredRow(row.getLong("id"), new ImportCommand.Row(0, row.getString("source"), row.getString("external_payment_id"), row.getBigDecimal("amount"), row.getString("currency"), row.getObject("received_on", LocalDate.class), row.getString("raw_reference"), row.getString("target_invoice_source"), row.getString("target_external_invoice_id"))), source, externalId);
    }
}
