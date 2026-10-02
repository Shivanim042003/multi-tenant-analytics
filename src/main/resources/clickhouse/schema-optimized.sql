-- Dedup design: ReplacingMergeTree collapses rows with an identical sorting key.
-- The key starts with tenant_id and ends with event_id, so (tenant_id, event_id) identifies an event,
-- and event_time (set by the producer, identical on every redelivery) is part of the key.
-- Queries use FINAL so duplicates are never visible, even before background merges run.
CREATE TABLE IF NOT EXISTS events
(
    tenant_id      LowCardinality(String),
    event_id       UUID,
    event_type     LowCardinality(String),
    event_time     DateTime64(3, 'UTC'),
    user_id        String,
    source         LowCardinality(String),
    amount         Nullable(Decimal(18, 4)),
    properties     String,
    schema_version UInt16,
    ingested_at    DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (tenant_id, event_time, event_id)
