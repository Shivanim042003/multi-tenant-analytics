# Resume bullets: where each placeholder comes from

| Placeholder | Source | How to obtain |
|---|---|---|
| events processed | JSON `pipeline.publishedEvents` / `ingest.acceptedEvents` | LoadGenerator final run |
| events/sec | JSON `ingest.eventsPerSec` (end-to-end: `pipeline.consumedEventsDuringWindow` / window) | LoadGenerator final run |
| 50 tenants | `--tenants=50` | LoadGenerator config |
| zero leaked records | `TenantIsolationIntegrationTest` passing | `mvn test` |
| chosen dedup mechanism | `ReplacingMergeTree` on `(tenant_id, event_time, event_id)` + `FINAL` reads | `docs/DEDUPLICATION.md` |
| 10K+ events, zero duplicates | `REPLAY ...` line and assertions in `ReplayReliabilityIntegrationTest` | `mvn test` output |
| p95 query latency | JSON `query.latencyMs.p95` | LoadGenerator final run |
| cache hit rate | JSON `cache.hitRate` | LoadGenerator final run |
| 15-minute load test | `--duration=900` | LoadGenerator config |
| skewed distribution | `--zipf=1.1` | LoadGenerator config |

Only write a number after you have the JSON that produced it. State the workload next to it in the README.
