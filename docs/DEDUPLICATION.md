# Deduplication

## The problem
Kafka → ClickHouse is at-least-once. If the consumer inserts a batch and then fails before committing the offset
(or the commit is lost in a rebalance), Kafka redelivers the batch and the events are inserted twice.
Producers can also retry, and clients can resend. Inserting twice must not change query results.

## Mechanism
- Events carry a client-supplied, stable `eventId`. The server never regenerates it, and truncates the timestamp to
  milliseconds so a redelivered event is byte-for-byte the same row.
- Table: `ReplacingMergeTree(ingested_at) ORDER BY (tenant_id, event_time, event_id)`.
  Rows with an identical sort key are collapsed to the latest one during background merges.
- Reads use `FINAL`, so duplicates are collapsed at query time even if no merge has happened yet.
- Identity is `(tenant_id, event_id)`: the same `eventId` in two tenants is two events.

## Why not the alternatives
| Option | Why not |
|---|---|
| Redis `SETNX` before insert | Adds a second system into the correctness path; a crash between SETNX and insert loses the event, between insert and SETNX allows a duplicate. |
| ClickHouse insert-block dedup | Only dedups identical blocks; redelivered batches are often split differently. |
| Exactly-once Kafka transactions | Do not extend across the ClickHouse write; the sink still has to be idempotent. |

## Definition used for any claim
A **duplicate in query results** is a `(tenant_id, event_id)` pair appearing more than once in `SELECT … FROM events FINAL`
(`sql/verify_duplicates.sql`, query 2). **Physical** duplicates (plain `count()` vs `FINAL`) are expected until merges
run and are reported separately; they are not a defect.

## Trade-offs
- `FINAL` has a CPU cost that grows with data volume. Sort-key pruning (tenant + time) keeps it bounded.
- Dedup only works if redeliveries carry the same `event_time`; the producer is the single source for it.
- Eventual physical cleanup depends on merges (`OPTIMIZE … FINAL` forces it; not needed for correctness).
