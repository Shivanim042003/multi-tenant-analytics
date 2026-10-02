# Results

> Intentionally empty. Fill this in only from saved runs in `benchmark-results/`.

## Environment
| Item | Value |
|---|---|
| Git commit | |
| Host CPU / RAM / OS | |
| Docker allocation | |
| Container limits | (paste `compose-resolved.yml` excerpt) |
| Dataset | rows= seed= anchor= |

## Reliability (from `mvn test`, ReplayReliabilityIntegrationTest output line `REPLAY ...`)
| Metric | Value |
|---|---|
| Unique events replayed | |
| Redelivered duplicates | |
| Physical rows | |
| Logical rows | |
| Logical duplicates | |

## Query optimization (baseline vs optimized, cache off)
| | p50 | p95 | p99 | granules read |
|---|---|---|---|---|
| baseline | | | | |
| optimized | | | | |

## Final benchmark (3 runs)
| Run | ingest events/s | query p95 (ms) | cache hit rate | errors |
|---|---|---|---|---|
| 1 | | | | |
| 2 | | | | |
| 3 | | | | |
