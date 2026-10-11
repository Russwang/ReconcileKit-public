\set ON_ERROR_STOP on
-- Run only after extended-demo.py on its new isolated synthetic ledger.
DO $$
BEGIN
    IF (SELECT count(*) FROM invoices) <> 3 OR
       (SELECT count(*) FROM payments) <> 4 OR
       (SELECT count(*) FROM allocations) <> 3 OR
       (SELECT count(*) FROM idempotency_records) <> 2 OR
       (SELECT count(*) FROM import_batches) <> 10 OR
       (SELECT count(*) FROM audit_events) <> 10 THEN
        RAISE EXCEPTION 'Unexpected extended demonstration fact counts';
    END IF;
    IF (SELECT count(*) FROM import_batches WHERE outcome='COMMITTED') <> 8 OR
       (SELECT count(*) FROM import_batches WHERE outcome='REJECTED') <> 2 OR
       (SELECT count(*) FROM audit_events WHERE event_type='IMPORT_COMMITTED') <> 8 THEN
        RAISE EXCEPTION 'Unexpected committed/rejected import attempts or success audits';
    END IF;
    IF EXISTS (
        SELECT 1 FROM payments p LEFT JOIN allocations a ON a.payment_id=p.id
        GROUP BY p.id, p.external_payment_id, p.amount
        HAVING CASE p.external_payment_id
          WHEN 'PAY-001' THEN p.amount <> 200.00 OR COALESCE(sum(a.amount),0) <> 200.00
          WHEN 'PAY-002' THEN p.amount <> 60.00 OR COALESCE(sum(a.amount),0) <> 60.00
          WHEN 'PARTIAL-M10' THEN p.amount <> 2.00 OR COALESCE(sum(a.amount),0) <> 0
          WHEN 'LOST-RESPONSE-M10' THEN p.amount <> 2.50 OR COALESCE(sum(a.amount),0) <> 0
          ELSE true END
    ) THEN RAISE EXCEPTION 'Unexpected payment contents or remaining balances'; END IF;
    IF EXISTS (
        SELECT 1 FROM invoices i LEFT JOIN allocations a ON a.invoice_id=i.id
        GROUP BY i.id, i.external_invoice_id, i.amount
        HAVING CASE i.external_invoice_id
          WHEN 'INV-101' THEN i.amount <> 120.00 OR COALESCE(sum(a.amount),0) <> 120.00
          WHEN 'INV-102' THEN i.amount <> 80.00 OR COALESCE(sum(a.amount),0) <> 80.00
          WHEN 'INV-103' THEN i.amount <> 100.00 OR COALESCE(sum(a.amount),0) <> 60.00
          ELSE true END
    ) THEN RAISE EXCEPTION 'Unexpected invoice contents or remaining balances'; END IF;
END $$;
SELECT 'PASS: exact extended facts, import outcomes and independent golden/pending balances' AS result;
SELECT outcome, count(*) AS attempts FROM import_batches GROUP BY outcome ORDER BY outcome;
