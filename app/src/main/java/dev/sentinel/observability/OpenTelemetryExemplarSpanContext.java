package dev.sentinel.observability;

import io.prometheus.metrics.tracer.common.SpanContext;

/**
 * Bridges the OpenTelemetry agent's active span into Micrometer's Prometheus registry so latency histograms
 * carry exemplars ({@code trace_id}). In Grafana this turns a point on a P99 panel into a link to the trace in Tempo.
 *
 * <p>Spring Boot only auto-configures exemplars when Micrometer Tracing is on the classpath. This lab uses the
 * OpenTelemetry Java agent for tracing instead, so the bridge is provided explicitly.
 */
public class OpenTelemetryExemplarSpanContext implements SpanContext {

    @Override
    public String getCurrentTraceId() {
        return TraceContext.currentTraceId().orElse(null);
    }

    @Override
    public String getCurrentSpanId() {
        return TraceContext.currentSpanId().orElse(null);
    }

    @Override
    public boolean isCurrentSpanSampled() {
        return TraceContext.isSampled();
    }

    @Override
    public void markCurrentSpanAsExemplar() {
    }
}
