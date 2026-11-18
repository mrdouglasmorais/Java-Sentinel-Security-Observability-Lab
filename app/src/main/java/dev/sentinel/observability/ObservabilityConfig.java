package dev.sentinel.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.prometheus.metrics.tracer.common.SpanContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    public static final String INSTRUMENTATION_SCOPE = "dev.sentinel";

    /**
     * Resolves to the agent's SDK when the OpenTelemetry Java agent is attached, otherwise to a no-op tracer.
     */
    @Bean
    Tracer sentinelTracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    @Bean
    SpanContext prometheusExemplarSpanContext() {
        return new OpenTelemetryExemplarSpanContext();
    }
}
