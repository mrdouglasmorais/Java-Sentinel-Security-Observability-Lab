# Scenario 01 — Variable latency in a dependency

**Question it answers:** the average looks fine, so why are users complaining?

## What gets injected

Preset `latency-jitter` on the risk engine: 80ms base latency + uniform jitter up to 220ms, and 8% of calls spike to
1.2s. The natural latency of the simulated engine is 15–40ms.

## Run it

```bash
docker compose --profile load run --rm k6 run /scripts/baseline.js   # terminal 1, 15 req/s
scripts/lab.sh scenario latency-jitter                               # terminal 2, after ~2 minutes
scripts/lab.sh reset                                                 # after ~3 minutes
```

Open **Sentinel — APM & Security**; a red annotation marks the fault window.

## What to look at

| Panel | Baseline | During jitter |
|---|---|---|
| Transfer P50 | ~35ms | ~235ms (6.7×) |
| Transfer P99 | ~50ms | **~1.39s (28×)** |
| Mean | close to P50 | far below P99: the mean hides the tail |
| 5xx | 0 | 0 |
| Retry amplification | 1.00 | 1.06–1.13 |
| Circuit breaker | CLOSED | CLOSED, slow call rate rises but stays below 80% |

P99 grows ~4× more than P50. A 1.2s spike exceeds the 800ms time limiter, so that attempt is cancelled and retried
after a jittered backoff: the user sees ~800ms + backoff + a normal attempt. Without the time limiter, the spike
would reach the user in full; without jitter in the backoff, retries would arrive in synchronised waves.

## Investigate

1. **Transfer latency panel → exemplar** (diamond) near the P99 line → *Query with Tempo*.
2. In the trace, `risk assessment` contains **two `risk-engine POST /v1/score` spans**: the first one ends at ~800ms
   with status *cancelled by caller timeout* and `sentinel.fault.injected_latency_ms ≈ 1200`, the second is fast.
3. TraceQL for the population: `{ name = "risk assessment" && span.sentinel.risk.attempts > 1 }`.
4. **Ground truth vs. reaction** timeline: the fault (top) and the circuit state (bottom). The breaker stays
   closed; this is a latency problem, not an availability problem.

## Takeaways

- Alert on percentiles (`SentinelTransferLatencyP99High`), never on averages.
- Time limits turn unbounded tail latency into bounded latency, and retries convert part of it into success.
- Retries are not free: amplification is plotted so the extra load on the dependency is visible.
