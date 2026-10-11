\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
    'invoices', (SELECT COUNT(*) FROM invoices),
    'payments', (SELECT COUNT(*) FROM payments),
    'allocations', (SELECT COUNT(*) FROM allocations),
    'audits', (SELECT COUNT(*) FROM audit_events),
    'idempotencyRecords', (SELECT COUNT(*) FROM idempotency_records),
    'importBatches', (SELECT COUNT(*) FROM import_batches),
    'sortedResponseBodySha256', encode(sha256(convert_to(COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'operationType', operation_type, 'key', key,
            'status', response_status, 'body', response_body
        ) ORDER BY operation_type, key)::text FROM idempotency_records
    ), '[]'), 'UTF8')), 'hex')
);
COMMIT;
