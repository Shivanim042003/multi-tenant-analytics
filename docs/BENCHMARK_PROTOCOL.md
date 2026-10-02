# Benchmark protocol

Rule: the environment is frozen before the final run. Any change (code, config, resource limits, dataset) means re-running.

## Freeze checklist
- [ ] `git status` clean; record `git rev-parse HEAD`
- [ ] Stack started from scratch with `docker compose -f docker/docker-compose.yml -f docker/docker-compose.bench.yml --profile app up -d --build`
- [ ] `docker compose … config > benchmark-results/compose-resolved.yml` saved (resource limits recorded)
- [ ] Docker Desktop CPU/memory allocation, host CPU/RAM/OS written in `docs/RESULTS.md`
- [ ] Dataset: same `SeedData` args as the optimization run; row count recorded
- [ ] Nothing else heavy running on the machine; the load generator runs on the host, outside the limited containers

## Runs
1. Warm-up is built in (`--warmup`, excluded from statistics).
2. `java tools/LoadGenerator.java --mode=mixed --duration=900 --warmup=30 --tenants=50 --seed=42 --zipf=1.1 --anchor=<same as seed>`
3. Repeat 3 times; report all three, not the best one.
4. Keep every JSON in `benchmark-results/`.

## What the JSON contains
- ingest: accepted events/sec, batch p50/p95/p99, errors
- pipeline: published vs consumed events, seconds to drain after load stops (end-to-end lag)
- query: queries/sec, p50/p95/p99, errors
- cache: hits, misses, errors, hit rate (hits / (hits + misses))
- config + environment (seed, zipf, threads, java, cpus)

## Workload definition (document this next to the numbers)
50 tenants, tenant and query shape both Zipf-distributed (`--zipf`), 25 shapes
(5 endpoints × windows of 1h/6h/24h/7d/30d ending at `--anchor`), identical requests repeat so they can hit the cache.
