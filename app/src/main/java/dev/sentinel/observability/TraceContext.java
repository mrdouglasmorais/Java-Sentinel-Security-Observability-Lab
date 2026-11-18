package dev.sentinel.observability;

import java.util.Optional;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Read-only access to the active OpenTelemetry span context. Spans are created by the Java agent; without it
 * (unit tests, plain {@code java -jar}) the API is a no-op and these methods return empty values.
 */
public final class TraceContext {

    private TraceContext() {
    }

    public static Optional<String> currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getTraceId()) : Optional.empty();
    }

    public static Optional<String> currentSpanId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getSpanId()) : Optional.empty();
    }

    public static boolean isSampled() {
        return Span.current().getSpanContext().isSampled();
    }
}
