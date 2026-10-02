-- Index/partition pruning evidence for docs/OPTIMIZATION.md. Replace the tenant and time range as needed.
-- Run:  docker exec -i analytics-clickhouse clickhouse-client --user analytics --password analytics -d analytics --multiquery < sql/explain_queries.sql

EXPLAIN indexes = 1
SELECT count() FROM events FINAL
WHERE tenant_id = 'tenant-001'
  AND event_time >= now() - INTERVAL 24 HOUR AND event_time < now();

SELECT table, sum(rows) AS rows, formatReadableSize(sum(bytes_on_disk)) AS on_disk, count() AS parts
FROM system.parts WHERE active AND database = 'analytics' AND table = 'events' GROUP BY table;
