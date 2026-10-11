\set ON_ERROR_STOP on
-- Hash every column, including timestamp precision and unparsed TEXT audit/response values.
-- A fixed rendering environment makes hashes comparable across source and restored databases.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL DateStyle = 'ISO, YMD';
SET LOCAL extra_float_digits = 3;
WITH rows AS (
    SELECT 'invoices' AS table_name, row_to_json(t)::text AS row_value FROM invoices t
    UNION ALL SELECT 'payments', row_to_json(t)::text FROM payments t
    UNION ALL SELECT 'allocations', row_to_json(t)::text FROM allocations t
    UNION ALL SELECT 'audit_events', row_to_json(t)::text FROM audit_events t
    UNION ALL SELECT 'idempotency_records', row_to_json(t)::text FROM idempotency_records t
    UNION ALL SELECT 'import_batches', row_to_json(t)::text FROM import_batches t
), names(table_name) AS (
    VALUES ('invoices'), ('payments'), ('allocations'), ('audit_events'),
           ('idempotency_records'), ('import_batches')
), snapshots AS (
    SELECT names.table_name, jsonb_build_object(
        'count', count(rows.row_value),
        'sortedRowSha256', COALESCE(jsonb_agg(
            encode(sha256(convert_to(rows.row_value, 'UTF8')), 'hex')
            ORDER BY encode(sha256(convert_to(rows.row_value, 'UTF8')), 'hex') COLLATE "C"
        ) FILTER (WHERE rows.row_value IS NOT NULL), '[]'::jsonb),
        'canonicalRowsSha256', encode(sha256(convert_to(COALESCE(
            string_agg(rows.row_value, E'\n' ORDER BY rows.row_value COLLATE "C"), ''), 'UTF8')), 'hex')
    ) AS facts FROM names LEFT JOIN rows USING (table_name) GROUP BY names.table_name
)
SELECT jsonb_object_agg(table_name, facts ORDER BY table_name) FROM snapshots;
COMMIT;
