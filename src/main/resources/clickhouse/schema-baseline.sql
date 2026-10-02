-- Deliberately UNOPTIMIZED layout for the before/after experiment (docs/OPTIMIZATION.md):
-- no partitioning, and a sorting key that cannot prune by tenant or time.
-- Dedup semantics are unchanged: (event_id, tenant_id) is still unique.
CREATE TABLE IF NOT EXISTS events
(
    tenant_id      String,
    event_id       UUID,
    event_type     String,
    event_time     DateTime64(3, 'UTC'),
    user_id        String,
    source         String,
    amount         Nullable(Decimal(18, 4)),
    properties     String,
    schema_version UInt16,
    ingested_at    DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
ORDER BY (event_id, tenant_id)
