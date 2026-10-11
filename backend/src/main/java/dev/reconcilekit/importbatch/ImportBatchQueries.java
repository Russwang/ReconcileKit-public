package dev.reconcilekit.importbatch;

import dev.reconcilekit.api.ApiException;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ImportBatchQueries {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ImportBatchQueries(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public ImportReport report(UUID attemptId) {
        var reports = jdbc.query("SELECT attempt_id,source,content_hash,outcome,total,inserted,duplicate,not_imported,row_errors,error_summary,request_id,created_at FROM import_batches WHERE attempt_id=?",
                (row, number) -> {
                    var errors = new ArrayList<ImportReport.RowError>();
                    for (var error : mapper.readTree(row.getString("row_errors")))
                        errors.add(new ImportReport.RowError(error.get("row").asInt(), error.get("code").asString(), error.get("message").asString()));
                    return new ImportReport(row.getObject("attempt_id", UUID.class), row.getString("source"), row.getString("content_hash"), row.getString("outcome"),
                            row.getInt("total"), row.getInt("inserted"), row.getInt("duplicate"), row.getInt("not_imported"), errors,
                            row.getString("error_summary"), row.getString("request_id"), row.getTimestamp("created_at").toInstant().toString());
                }, attemptId);
        if (reports.isEmpty()) throw ApiException.missing("Import attempt not found");
        return reports.getFirst();
    }
}
