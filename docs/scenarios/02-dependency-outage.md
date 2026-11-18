# Scenario 02 — Dependency outage and graceful degradation

**Question it answers:** when a dependency dies, do we die with it?

## What gets injected

Preset `dependency-outage`: 90% of risk engine calls fail.

## Run it

```bash
docker compose --profile load run --rm k6 run /scripts/baseline.js
scripts/lab.sh scenario dependency-outage
scripts/lab.sh reset            # watch the recovery through HALF_OPEN
```

## What happens

1. Retries absorb the first failures (amplification spikes towards 3×).
2. After ≥10 calls with ≥50% failures in the last 20, the **circuit opens within seconds**. Calls now fail
   immediately with `CallNotPermittedException`, which is *not* retried.
3. `RiskPolicy` takes over ([ADR 0003](../architecture/adr/0003-resilience-and-degradation.md)):
   - transfers ≤ 100.00 → `201 COMPLETED`, decision `fallback_low_value:circuit_open`;
   - larger transfers → `202 PENDING_REVIEW`, decision `fallback_manual_review:circuit_open`, no funds move.
4. Every 15s the breaker lets 5 trial calls through (HALF_OPEN); while the fault persists it reopens.
5. After `reset`, the next half-open trial succeeds and the breaker closes.

| Panel | Observation |
|---|---|
| Error rate 5xx | **0**: the API stays available |
| Transfer latency | drops below baseline while open (no remote call at all) |
| Risk assessment outcomes | `scored` → `error` → `circuit_open` |
| Transfers by decision | `risk_approved` replaced by `fallback_low_value:*` and `fallback_manual_review:*` |
| Circuit state | OPEN, with periodic HALF_OPEN blips |

The `SentinelRiskEngineCircuitOpen` alert fires after one minute.

## Investigate

- TraceQL: `{ span.sentinel.transfer.decision =~ "fallback.*" }`. The `risk assessment` span has
  `sentinel.risk.outcome = circuit_open`, `sentinel.circuit_breaker.state = OPEN` and no client span at all.
- The ground truth timeline (fault on) next to the circuit timeline shows the reaction delay: seconds, not minutes.

## Takeaways

- Fail fast beats fail slow: an open circuit returns in microseconds instead of holding threads for timeouts.
- Degradation is a business decision expressed as code (`RiskPolicy`) and made visible in responses, metrics and
  spans, so nobody discovers the fallback in production by surprise.
- Do not retry what cannot succeed (open circuit, full bulkhead).
