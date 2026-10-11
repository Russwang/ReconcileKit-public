\set ON_ERROR_STOP on
-- Independent SQL, without the application balance/query methods.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payments p LEFT JOIN allocations a ON a.payment_id=p.id
        GROUP BY p.id, p.amount HAVING COALESCE(SUM(a.amount),0) < 0 OR COALESCE(SUM(a.amount),0) > p.amount
    ) THEN RAISE EXCEPTION 'Payment allocation invariant violated'; END IF;
    IF EXISTS (
        SELECT 1 FROM invoices i LEFT JOIN allocations a ON a.invoice_id=i.id
        GROUP BY i.id, i.amount HAVING COALESCE(SUM(a.amount),0) < 0 OR COALESCE(SUM(a.amount),0) > i.amount
    ) THEN RAISE EXCEPTION 'Invoice allocation invariant violated'; END IF;
    IF EXISTS (SELECT 1 FROM idempotency_records WHERE response_status IS NULL OR response_body IS NULL)
    THEN RAISE EXCEPTION 'Committed pending idempotency record'; END IF;
    IF EXISTS (
        SELECT a.operation_id FROM allocations a LEFT JOIN audit_events e ON e.operation_id=a.operation_id
        GROUP BY a.operation_id HAVING COUNT(DISTINCT e.id) <> 1
    ) THEN RAISE EXCEPTION 'Allocation success audit missing or duplicated'; END IF;
END $$;
SELECT 'PASS: payment/invoice bounds, no pending keys, one audit per allocation operation' AS result;
SELECT (SELECT COUNT(*) FROM invoices) AS invoices, (SELECT COUNT(*) FROM payments) AS payments,
       (SELECT COUNT(*) FROM allocations) AS allocations, (SELECT COUNT(*) FROM audit_events) AS audits,
       (SELECT COUNT(*) FROM idempotency_records) AS idempotency_records;
