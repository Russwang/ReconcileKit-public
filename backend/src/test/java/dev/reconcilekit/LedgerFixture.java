package dev.reconcilekit;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic, isolated test data only; never wired into production. */
public class LedgerFixture {
    final JdbcTemplate jdbc;
    final UUID batch=UUID.randomUUID();
    public LedgerFixture(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        jdbc.execute("TRUNCATE audit_events,idempotency_records,allocations,payments,invoices,import_batches RESTART IDENTITY");
        jdbc.update("INSERT INTO import_batches(attempt_id,source,content_hash,outcome,total,inserted,duplicate,not_imported,request_id) VALUES(?,'fixture',?,'COMMITTED',0,0,0,0,'fixture')",batch,"a".repeat(64));
    }
    public long invoice(String external,String amount) {
        return jdbc.queryForObject("INSERT INTO invoices(source,external_invoice_id,amount,currency,issued_on,first_batch_id,source_row) VALUES('demo-invoices',?,?,'GBP','2026-10-01',?,1) RETURNING id",Long.class,external,new BigDecimal(amount),batch);
    }
    public long payment(String external,String amount) {
        return jdbc.queryForObject("INSERT INTO payments(source,external_payment_id,amount,currency,received_on,first_batch_id,source_row) VALUES('demo-payments',?,?,'GBP','2026-10-02',?,1) RETURNING id",Long.class,external,new BigDecimal(amount),batch);
    }
    public void invariants() {
        for (String parent : new String[]{"payments", "invoices"}) {
            String foreign=parent.equals("payments")?"payment_id":"invoice_id";
            int violations=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT p.id FROM "+parent+" p LEFT JOIN allocations a ON a."+foreign+"=p.id GROUP BY p.id,p.amount HAVING COALESCE(SUM(a.amount),0)<0 OR COALESCE(SUM(a.amount),0)>p.amount) v",Integer.class);
            org.junit.jupiter.api.Assertions.assertEquals(0,violations,parent);
        }
    }
}
