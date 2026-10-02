# Multi-Tenant Real-Time Analytics Platform

Java 21 · Spring Boot 3.5 · Kafka (KRaft) · ClickHouse · Redis · Docker · Testcontainers

Authenticated, tenant-isolated ingestion and query APIs. Events flow `API → Kafka → consumer → ClickHouse`;
queries read ClickHouse through a tenant-scoped Redis cache.

```
 client ──X-API-Key──▶ Spring Boot API ──▶ Kafka (key = tenantId) ──▶ batch consumer ──▶ ClickHouse
                          │  tenant from                              commit offset          ReplacingMergeTree
                          │  credential only                          AFTER insert           ORDER BY (tenant_id, event_time, event_id)
                          ▼
                   analytics API ──▶ Redis (tenant:{id}:q:...) ──miss──▶ ClickHouse (… FINAL)
```

## Design in one page

| Concern | Decision |
|---|---|
| Tenant identity | Derived only from the API key (`ApiKeyAuthenticationFilter`), held in `TenantContext`, cleared in `finally`. `EventRequest` has no `tenantId` field; query endpoints have no tenant parameter. |
| Delivery | At-least-once: offsets are committed only after the ClickHouse insert succeeds. Infrastructure failures are retried forever (no skipped events); malformed records go to a DLQ topic. |
| Deduplication | `ReplacingMergeTree`, sort key `(tenant_id, event_time, event_id)`; every query uses `FINAL`. Identity is `(tenant_id, event_id)`. See `docs/DEDUPLICATION.md`. |
| Caching | Cache-aside, key `tenant:{tenantId}:q:{query}:{hash(params)}`, TTL 30 s. Redis errors never fail a request (they fall through to ClickHouse). |
| Schema | Created at startup (`schema-optimized.sql`; `schema-baseline.sql` exists for the optimization experiment). |

## Run it

Prerequisites: JDK 21, Maven 3.9+, Docker. On Windows PowerShell use `curl.exe`, not `curl`.

```bash
docker compose -f docker/docker-compose.yml up -d          # Kafka, Redis, ClickHouse (healthchecks)
mvn spring-boot:run                                         # app on :8080 (creates topics + table)
curl http://localhost:8080/actuator/health
```

Everything in containers (also the base for benchmarking):

```bash
docker compose -f docker/docker-compose.yml --profile app up -d --build
```

Dev API keys: with `DEV_TENANT_COUNT=50` (default in dev) tenant `tenant-001` uses key `dev-key-tenant-001`, and so on.

```bash
# ingest (202 = durably in Kafka, not yet queryable)
curl -X POST localhost:8080/api/v1/events -H "X-API-Key: dev-key-tenant-001" -H "Content-Type: application/json" \
  -d '{"eventId":"11111111-1111-1111-1111-111111111111","eventType":"purchase","timestamp":"2026-10-01T12:00:00Z","userId":"u1","source":"web","amount":19.99}'

# query (window must be < 92 days)
curl -H "X-API-Key: dev-key-tenant-001" "localhost:8080/api/v1/analytics/count?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z"
```

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/events`, `POST /api/v1/events/batch` (≤500) | ingest |
| `GET /api/v1/analytics/count` (`eventType` optional) | event count |
| `GET /api/v1/analytics/by-type` | counts per event type |
| `GET /api/v1/analytics/timeseries?bucket=hour\|day` | time-bucketed counts |
| `GET /api/v1/analytics/revenue` | sum of `amount` |
| `GET /api/v1/analytics/top-users?limit=10` (≤100) | top-N users |

## Tests (need Docker)

```bash
mvn test
```

Real Kafka, ClickHouse and Redis via Testcontainers. Note for Docker 29+ with Testcontainers 1.21.x: put
`api.version=1.44` in `~/.docker-java.properties`.

| Test | Proves |
|---|---|
| `EventApiIntegrationTest` | 401 without/with bad key; body `tenantId` is ignored; validation (400s); batch limits |
| `TenantIsolationIntegrationTest` | A never sees B's data via DB or cache; cache keys are tenant-prefixed |
| `DeduplicationIntegrationTest` | same event 5× → 1 logical row; same `eventId` in two tenants → 2 events |
| `ConsumerFailureIntegrationTest` | failures before/after insert lose nothing, 0 logical duplicates |
| `ReplayReliabilityIntegrationTest` | 10,000 unique + 2,500 redelivered events with injected failures → exactly 10,000 logical rows |
| `AnalyticsApiIntegrationTest` | every analytics endpoint, validation, cache hit |
| `QueryCacheTest`, `ApiKeyRegistryTest`, model/tenant tests | unit level, incl. Redis-down fallback and thread isolation |

## Load, seed, benchmark

```bash
java tools/SeedData.java --rows=10000000 --tenants=50 --days=30 --seed=42 --anchor=2026-10-01T00:00:00Z
java tools/LoadGenerator.java --mode=mixed --duration=900 --warmup=30 --tenants=50 --seed=42 --zipf=1.1 --anchor=2026-10-01T00:00:00Z
```

Method, environment freezing and result recording: `docs/BENCHMARK_PROTOCOL.md`, `docs/OPTIMIZATION.md`.
**No performance number appears anywhere in this repository until a run has produced it** (`docs/RESULTS.md` is empty on purpose).

## Known trade-offs

- Queries use `FINAL`: always correct, costs CPU at very large scale (see `docs/DEDUPLICATION.md`).
- Cached results can be up to the TTL stale.
- `202` means accepted into Kafka, not yet visible; typical lag is sub-second but is not guaranteed.
- Actuator metrics endpoints are open for local benchmarking; protect them in production.
- Single-broker dev setup (replication factor 1).
