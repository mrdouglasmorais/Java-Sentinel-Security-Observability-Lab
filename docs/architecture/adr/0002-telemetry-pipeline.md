# ADR 0002 — Telemetry pipeline

**Status:** accepted

## Context

Options for a Spring Boot 4 application:

1. **OpenTelemetry Java agent**: zero-code instrumentation of HTTP server, JDBC, logging and executors; bytecode
   manipulation at startup.
2. **Spring Boot's `spring-boot-starter-opentelemetry`**: Micrometer Observation bridged to the OpenTelemetry SDK,
   no agent; instrumentation limited to what emits Micrometer observations (JDBC needs an extra library).
3. **Micrometer only** with Prometheus, no tracing.

The lab must show traces with database spans, logs correlated with traces and metrics with exemplars, and it should
look like what teams actually run in production.

## Decision

- **Traces and logs: OpenTelemetry Java agent** (version pinned in `pom.xml`, copied into the image by Maven, not
  downloaded ad hoc). OTLP/HTTP to an OpenTelemetry Collector, which scrubs sensitive attributes, drops probe traces
  and fans out to Tempo and Loki.
- **Metrics: Micrometer → Prometheus scrape** of `/actuator/prometheus` on an internal management port. Spring Boot,
  HikariCP, Resilience4j and the JVM already publish rich Micrometer metrics; the agent's metric exporter is disabled
  to avoid duplicates (`OTEL_METRICS_EXPORTER=none`).
- **Exemplars bridge**: Spring Boot only wires Prometheus exemplars when Micrometer Tracing is present. A ~20-line
  `OpenTelemetryExemplarSpanContext` exposes the agent's current span to the Prometheus registry, so every latency
  histogram bucket carries a `trace_id`.
- **Manual spans** with the OpenTelemetry API (provided by the agent at runtime, no-op without it) for business steps:
  `transfer execute`, `risk assessment`, `risk-engine POST /v1/score`.
- **Stable semantic conventions** for HTTP (default) and database (`OTEL_SEMCONV_STABILITY_OPT_IN=database`).

## Consequences

- The application runs without the agent (tests, IDE): spans become no-ops, metrics still work.
- Metric names follow Micrometer conventions (`http_server_requests_seconds_*`), not OpenTelemetry's
  (`http_server_request_duration_seconds_*`). Tempo's span metrics (`traces_spanmetrics_*`) provide the OTel view.
- Logs are written twice: ECS JSON on stdout (for `docker logs` and any log shipper) and OTLP from the agent's Logback
  appender. In production, pick one.

## Cardinality rules

- Metric tags are bounded enums: `status`, `outcome`, `type`, `endpoint_group`, `decision`. Never user ids, IPs,
  account ids, idempotency keys or raw paths.
- High-cardinality detail belongs in logs (structured metadata in Loki) and span attributes.
- Histograms are bounded (`minimum-expected-value` 5ms, `maximum-expected-value` 10s).
