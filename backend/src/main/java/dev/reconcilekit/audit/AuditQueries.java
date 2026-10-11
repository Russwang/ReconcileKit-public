package dev.reconcilekit.audit;

import dev.reconcilekit.query.LedgerQueries;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditQueries {
    public record Event(long id, UUID operationId, String actor, String eventType, String aggregateId, JsonNode detail, String createdAt) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AuditQueries(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public LedgerQueries.Page<Event> events(int page, int size) {
        LedgerQueries.page(page, size);
        return new LedgerQueries.Page<>(jdbc.query("SELECT id,operation_id,actor,event_type,aggregate_id,detail,created_at FROM audit_events ORDER BY id LIMIT ? OFFSET ?",
                (row, number) -> new Event(row.getLong("id"), row.getObject("operation_id", UUID.class), row.getString("actor"), row.getString("event_type"),
                        row.getString("aggregate_id"), mapper.readTree(row.getString("detail")), row.getTimestamp("created_at").toInstant().toString()), size, (long) page * size), page, size);
    }
}
