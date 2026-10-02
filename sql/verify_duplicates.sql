-- Run:  docker exec -i analytics-clickhouse clickhouse-client --user analytics --password analytics -d analytics --multiquery < sql/verify_duplicates.sql

-- 1) Physical rows vs logical rows (physical duplicates are expected until background merges run)
SELECT
    (SELECT count() FROM events)        AS physical_rows,
    (SELECT count() FROM events FINAL)  AS logical_rows;

-- 2) THE DEFINITION USED FOR THE RESUME CLAIM:
--    duplicates visible in the logical (FINAL) view = rows sharing a (tenant_id, event_id). Must be 0.
SELECT count() - uniqExact(tenant_id, event_id) AS logical_duplicates FROM events FINAL;

-- 3) Per-tenant breakdown if the number above is ever non-zero
SELECT tenant_id, count() - uniqExact(event_id) AS dup_rows
FROM events FINAL GROUP BY tenant_id HAVING dup_rows > 0;
