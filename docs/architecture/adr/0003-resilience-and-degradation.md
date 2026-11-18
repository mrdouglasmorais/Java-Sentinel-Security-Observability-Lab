# ADR 0003 — Resilience order, budgets and degradation policy

**Status:** accepted

## Context

Every transfer asks the risk engine for a score. The risk engine can be slow, erratic or down. Without protection,
its latency becomes the API's latency and its outage becomes the API's outage. With naive protection (unbounded
retries, retries inside a transaction), the API makes the incident worse.

## Decision

### Call it outside the database transaction

`TransferService` calls the risk engine before `BEGIN`. Holding a pooled connection and row locks while waiting on a
remote call turns dependency latency into connection pool exhaustion for *every* endpoint.

### Decorator order (outermost first)

```
Retry → CircuitBreaker → Bulkhead → TimeLimiter → call
```

| Mechanism | Setting | Why |
|---|---|---|
| TimeLimiter | 800ms, cancels the running call | An attempt never holds the caller longer than this. |
| Bulkhead (semaphore) | 25 concurrent, no waiting | Virtual threads remove thread pools as an implicit limit; this is the explicit one. |
| CircuitBreaker | count window 20, min 10 calls, 50% failures or 80% calls slower than 600ms, open 15s | Stops calling a dependency that is clearly unhealthy; every attempt is counted. |
| Retry | 3 attempts, 100ms × 2ⁿ, ±50% random jitter | Absorbs transient failures without synchronised retry waves; never retries an open circuit or a full bulkhead. |

Worst case perceived latency is bounded: 3 × 800ms + ~100ms + ~200ms of backoff ≈ 2.7s, after which the policy decides.

### Degradation policy (`RiskPolicy`)

| Risk engine | Amount | Outcome |
|---|---|---|
| score < 80 | any | `COMPLETED` |
| score ≥ 80 | any | `REJECTED` (422) |
| unavailable | ≤ 100.00 | `COMPLETED`, decision `fallback_low_value:<cause>` (fail open, bounded loss) |
| unavailable | > 100.00 | `PENDING_REVIEW` (202), no funds move (fail closed) |

The limit is configuration (`sentinel.payments.risk.fallback-approval-limit`), the decision is visible in the API
response, in the `sentinel_transfers_total{decision}` metric and on the span.

## Consequences

- During an outage the API keeps answering in milliseconds (circuit open → immediate fallback) instead of timing out.
- Retries multiply load on a struggling dependency; the dashboard plots *retry amplification* (attempts per
  assessment) so the cost is visible, and an alert fires above 1.5×.
- Business owns the fallback limit; engineering owns the mechanisms. The ADR makes the split explicit.
