# ADR 0001 — Modular monolith with one simulated dependency

**Status:** accepted

## Context

The lab teaches tracing, resilience and security observability. Distributed systems make those topics visible, but
every extra service adds build time, memory, configuration and failure modes that have nothing to do with the lesson.

## Decision

- One deployable (`sentinel-api`) organised as a modular monolith: `identity`, `security`, `payments`, `resilience`,
  `simulation`, `observability`.
- Light hexagonal boundaries inside `payments`: domain and ports are framework-free; JDBC, REST, resilience and the
  simulated dependency are adapters.
- The external dependency (risk engine) is simulated in-process but behaves like a remote call: it blocks, has a
  latency distribution, can time out and fail, and is traced as a `CLIENT` span with `peer.service=risk-engine`.
  Tempo's service graph shows it as a separate node.

## Consequences

- `docker compose up` stays small enough for a laptop (one JVM, 1 GB limit).
- The trace of a transfer still has the shape of a distributed trace: server span → business spans → client spans
  (one per retry attempt) → database spans.
- What is *not* demonstrated: context propagation across process boundaries (W3C `traceparent` over HTTP). The agent
  handles it automatically when a real HTTP adapter replaces the simulated client; that swap is a single class.
