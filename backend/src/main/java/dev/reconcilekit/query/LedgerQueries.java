package dev.reconcilekit.query;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import dev.reconcilekit.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LedgerQueries {
    public record Page<T>(List<T> items, int page, int size) {}
    public record InvoiceView(long id, String source, String externalInvoiceId, String amount, String currency,
            String issuedOn, String allocated, String remaining, String status) {}
    public record PaymentView(long id, String source, String externalPaymentId, String amount, String currency,
            String receivedOn, String rawReference, String targetInvoiceSource, String targetExternalInvoiceId,
            String allocated, String remaining, String status) {}
    private final JdbcTemplate jdbc;
    public LedgerQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw ApiException.structure("page must be nonnegative; size must be 1–100");
    }
    public Page<InvoiceView> invoices(int page, int size) {
        page(page, size);
        return new Page<>(jdbc.query("SELECT i.*, COALESCE((SELECT SUM(a.amount) FROM allocations a WHERE a.invoice_id=i.id),0) AS allocated FROM invoices i ORDER BY i.id LIMIT ? OFFSET ?",
                (rs, n) -> invoice(rs), size, (long)page * size), page, size);
    }
    public Page<PaymentView> payments(int page, int size, boolean pending) {
        page(page, size);
        String sql = "SELECT p.*, COALESCE((SELECT SUM(a.amount) FROM allocations a WHERE a.payment_id=p.id),0) AS allocated FROM payments p";
        if (pending) sql += " WHERE p.amount > COALESCE((SELECT SUM(a.amount) FROM allocations a WHERE a.payment_id=p.id),0)";
        sql += " ORDER BY p.id LIMIT ? OFFSET ?";
        return new Page<>(jdbc.query(sql, (rs, n) -> payment(rs), size, (long)page * size), page, size);
    }
    public InvoiceView invoice(long id) {
        var rows = jdbc.query("SELECT i.*, COALESCE((SELECT SUM(a.amount) FROM allocations a WHERE a.invoice_id=i.id),0) AS allocated FROM invoices i WHERE id=?", (rs, n) -> invoice(rs), id);
        if (rows.isEmpty()) throw ApiException.missing("Invoice not found");
        return rows.getFirst();
    }
    public PaymentView payment(long id) {
        var rows = jdbc.query("SELECT p.*, COALESCE((SELECT SUM(a.amount) FROM allocations a WHERE a.payment_id=p.id),0) AS allocated FROM payments p WHERE id=?", (rs, n) -> payment(rs), id);
        if (rows.isEmpty()) throw ApiException.missing("Payment not found");
        return rows.getFirst();
    }
    private InvoiceView invoice(ResultSet rs) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount"), allocated = rs.getBigDecimal("allocated");
        return new InvoiceView(rs.getLong("id"), rs.getString("source"), rs.getString("external_invoice_id"), Money.format(amount), rs.getString("currency"), rs.getObject("issued_on", LocalDate.class).toString(),
                Money.format(allocated), Money.format(amount.subtract(allocated)), allocated.signum()==0 ? "OPEN" : allocated.compareTo(amount)==0 ? "PAID" : "PARTIALLY_PAID");
    }
    private PaymentView payment(ResultSet rs) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount"), allocated = rs.getBigDecimal("allocated");
        return new PaymentView(rs.getLong("id"), rs.getString("source"), rs.getString("external_payment_id"), Money.format(amount), rs.getString("currency"), rs.getObject("received_on", LocalDate.class).toString(),
                rs.getString("raw_reference"), rs.getString("target_invoice_source"), rs.getString("target_external_invoice_id"), Money.format(allocated), Money.format(amount.subtract(allocated)),
                allocated.signum()==0 ? "UNALLOCATED" : allocated.compareTo(amount)==0 ? "FULLY_ALLOCATED" : "PARTIALLY_ALLOCATED");
    }
}
