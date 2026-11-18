# Scenario 03 — Slow database and connection pool saturation

**Question it answers:** the application is healthy, CPU is idle, and yet everything is slow. Where is the time going?

## What gets injected

Preset `slow-database`: while locks on the accounts are held, the transfer transaction also runs `SELECT pg_sleep(...)`:
250ms base + up to 250ms of jitter, 5% spikes of 1.5s. It is a real statement, so it appears in traces, in
`pg_stat_statements` and holds a real pooled connection and real row locks, just as a slow query would.

## Run it

```bash
docker compose --profile load run --rm k6 run /scripts/baseline.js
scripts/lab.sh scenario slow-database        # after ~1 minute
scripts/lab.sh reset                         # after ~2 minutes
```

## Measured (15 req/s, pool of 10 connections)

| Metric | Baseline | Slow database |
|---|---|---|
| Transfer P50 | ~35ms | ~2.9s |
| Transfer P99 | ~50ms | **~9.4s** |
| Hikari active / pending | 1–2 / 0 | **10 / up to 40** |
| 5xx | 0 | ~9.5/s, **all 503** with `Retry-After: 1`, 0 × 500 |
| P99 one minute after `reset` | — | 53ms |

`SentinelDatabasePoolSaturated`, `SentinelTransferLatencyP99High` and `SentinelErrorBudgetFastBurn` fire.

## Investigate

1. **USE row**: Hikari *active* is flat at the maximum and *pending* climbs; CPU is low. Classic saturation: work
   is queued, not executing.
2. **Exemplar on the latency panel → trace.** A slow transfer shows where the time went: a gap before the first
   database span (waiting for a pooled connection), the `SELECT ... FOR NO KEY UPDATE` span waiting for row locks held
   by other transfers on the same hot accounts, then the slow `SELECT pg_sleep` span.
3. **Failed requests** carry `trace_id` in the problem response. Their logs show one of three causes:
   - `CannotGetJdbcConnectionException`: no connection within 2s (`connection-timeout`);
   - SQLState `55P03`, *canceling statement due to lock timeout* (`lock_timeout=2s`);
   - SQLState `57014`, *canceling statement due to statement timeout* (`statement_timeout=5s`).
4. **Database side**: `docker compose exec postgres psql -U sentinel -c "SELECT query, calls, mean_exec_time FROM
   pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 5"`.

## Findings worth discussing

**Timeouts compound.** Every limit is 2s, yet P99 reaches ~9.4s. A transfer acquires a connection three times
(idempotency lookup, account read, the transaction), and each acquisition has its own 2s budget, followed by up to
2s of lock wait and the slow statement itself: 3 × 2s + 2s + ~1.5s ≈ 9.5s. Per-layer timeouts bound each step,
not the request. A request-level deadline (propagated budget) or a single connection per request would bound the
total; it is on the roadmap as a follow-up scenario.

**Lock timeouts were initially reported as 500.** Spring does not translate PostgreSQL's `55P03`/`57014` into its
transient exception hierarchy, so the first run of this scenario after adding `lock_timeout` produced
`UncategorizedSQLException` → *500 internal error*. Loki showed it immediately
(`sum by (exception_type) (count_over_time({service_name="sentinel-api"} | exception_type!="" [5m]))`). They are now
mapped to 503, with a regression test (`TransferFlowIT.lockTimeoutIsReportedAsTemporarilyUnavailable`).

**Hot rows serialise.** Most of the load moves money between a handful of seeded accounts. Row locks serialise
transfers on the same account, so a slower critical section reduces throughput for those accounts, not
for the system as a whole. More connections would not help; a shorter critical section would.

## Takeaways

- Saturation shows up first as queueing (pending connections), only later as errors. Alert on the queue.
- Fail fast with explicit timeouts at every waiting point, and return 503 + `Retry-After`, not 500.
- Keep remote calls (the risk engine) out of the transaction; this scenario would be far worse otherwise.
