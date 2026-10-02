# Query optimization experiment (before / after)

Goal: a defensible before/after for ClickHouse query latency. Query tuning on a few thousand rows is meaningless
(ClickHouse is fast on tiny data regardless of layout), so use millions of rows.

1. **Baseline.** Start the app with `CLICKHOUSE_SCHEMA_VARIANT=baseline`
   (drop the table first: `docker exec analytics-clickhouse clickhouse-client --user analytics --password analytics -q "DROP TABLE IF EXISTS analytics.events"`). The baseline table has no partitioning and `ORDER BY (event_id, tenant_id)`.
2. Seed: `java tools/SeedData.java --rows=10000000 --seed=42 --anchor=<fixed>`.
3. Disable the cache for this run (`ANALYTICS_CACHE_ENABLED=false`) so you measure ClickHouse, not Redis.
4. Run `LoadGenerator --mode=query` (fixed seed, anchor, duration). Save the JSON. Capture `sql/explain_queries.sql` output.
5. **Optimized.** Switch `CLICKHOUSE_SCHEMA_VARIANT=optimized`, drop the table, re-seed with the *same* seed/anchor, repeat step 4.
6. Record p50/p95/p99 for both runs plus rows/granules read from `EXPLAIN indexes = 1`, in `docs/RESULTS.md`.

Further experiments, each as its own measured before/after: narrower SELECT lists, a materialized view for hourly
counts, `LowCardinality` columns, PARTITION granularity. Record what you tried even if it did not help.
