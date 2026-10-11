package dev.reconcilekit.reconciliation;

import dev.reconcilekit.api.Money;
import dev.reconcilekit.query.LedgerQueries;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A single SQL snapshot provides both balances and the exact-source target for review reasons. */
@Service
public class ReconciliationQueries {
    public record PendingPayment(long id, String source, String externalPaymentId, String amount, String currency,
            String receivedOn, String rawReference, String targetInvoiceSource, String targetExternalInvoiceId,
            String allocated, String remaining, String status, String reason) {}
    private final JdbcTemplate jdbc;
    public ReconciliationQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public LedgerQueries.Page<PendingPayment> pending(int page, int size) {
        LedgerQueries.page(page, size);
        String sql = """
                WITH payment_totals AS (SELECT payment_id, SUM(amount) allocated FROM allocations GROUP BY payment_id),
                     invoice_totals AS (SELECT invoice_id, SUM(amount) allocated FROM allocations GROUP BY invoice_id)
                SELECT p.*, COALESCE(pt.allocated,0) allocated, i.id target_id,
                       i.amount-COALESCE(it.allocated,0) target_remaining
                FROM payments p
                LEFT JOIN payment_totals pt ON pt.payment_id=p.id
                LEFT JOIN invoices i ON i.source=p.target_invoice_source AND i.external_invoice_id=p.target_external_invoice_id
                LEFT JOIN invoice_totals it ON it.invoice_id=i.id
                WHERE p.amount>COALESCE(pt.allocated,0)
                ORDER BY p.id LIMIT ? OFFSET ?
                """;
        var items = jdbc.query(sql, (row, number) -> {
            var amount = row.getBigDecimal("amount"); var allocated = row.getBigDecimal("allocated");
            var decision = AutomaticPolicy.decide(amount, allocated, row.getString("target_invoice_source"), row.getString("target_external_invoice_id"),
                    row.getObject("target_id", Long.class) != null, row.getBigDecimal("target_remaining"));
            return new PendingPayment(row.getLong("id"), row.getString("source"), row.getString("external_payment_id"), Money.format(amount), row.getString("currency"),
                    row.getObject("received_on", LocalDate.class).toString(), row.getString("raw_reference"), row.getString("target_invoice_source"), row.getString("target_external_invoice_id"),
                    Money.format(allocated), Money.format(amount.subtract(allocated)), allocated.signum() == 0 ? "UNALLOCATED" : "PARTIALLY_ALLOCATED", decision.reason());
        }, size, (long) page * size);
        return new LedgerQueries.Page<>(items, page, size);
    }
}
