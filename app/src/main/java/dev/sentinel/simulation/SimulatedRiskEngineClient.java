package dev.sentinel.simulation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import dev.sentinel.payments.risk.RiskEngineClient;
import dev.sentinel.payments.risk.RiskRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import org.springframework.stereotype.Component;

/**
 * Stand-in for a remote risk scoring service. It behaves like one from the caller's point of view: it blocks, has a
 * natural latency distribution, can time out and can fail. Each call is a {@code CLIENT} span with
 * {@code peer.service=risk-engine}, so Tempo draws it as a separate node in the service graph and every retry
 * attempt is visible in the trace.
 *
 * <p>Swapping it for a real HTTP adapter ({@code RestClient}) does not change the rest of the application.
 */
@Component
public class SimulatedRiskEngineClient implements RiskEngineClient {

    private static final Duration NATURAL_LATENCY_MIN = Duration.ofMillis(15);
    private static final Duration NATURAL_LATENCY_SPREAD = Duration.ofMillis(25);

    private final FaultInjector faults;
    private final Tracer tracer;
    private final MeterRegistry registry;

    public SimulatedRiskEngineClient(FaultInjector faults, Tracer tracer, MeterRegistry registry) {
        this.faults = faults;
        this.tracer = tracer;
        this.registry = registry;
    }

    @Override
    public int score(RiskRequest request) {
        Span span = tracer.spanBuilder("risk-engine POST /v1/score")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("peer.service", "risk-engine")
                .setAttribute("server.address", "risk-engine.internal")
                .setAttribute("http.request.method", "POST")
                .startSpan();
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try (Scope _ = span.makeCurrent()) {
            Duration injected = faults.sampleLatency(FaultTarget.RISK_ENGINE);
            span.setAttribute("sentinel.fault.injected_latency_ms", injected.toMillis());
            Thread.sleep(naturalLatency().plus(injected));

            if (faults.sampleFailure(FaultTarget.RISK_ENGINE)) {
                outcome = "error";
                span.setAttribute("http.response.status_code", 503);
                throw new RiskEngineException("risk-engine responded 503 Service Unavailable");
            }
            int score = scoreFor(request.amount());
            span.setAttribute("http.response.status_code", 200);
            span.setAttribute("sentinel.risk.score", score);
            return score;
        } catch (InterruptedException cancelledByTimeLimiter) {
            Thread.currentThread().interrupt();
            outcome = "cancelled";
            throw new RiskEngineException("risk-engine call cancelled");
        } catch (RiskEngineException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getMessage());
            throw failure;
        } finally {
            if (outcome.equals("cancelled")) {
                span.setStatus(StatusCode.ERROR, "cancelled by caller timeout");
            }
            sample.stop(Timer.builder("sentinel.risk.engine.calls")
                    .description("Individual calls (attempts) to the risk engine dependency")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry));
            span.end();
        }
    }

    /**
     * Deterministic scoring so demos are reproducible: large amounts are risky.
     */
    static int scoreFor(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("50000")) > 0) {
            return 92;
        }
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            return 61;
        }
        return 5 + amount.intValue() % 20;
    }

    private static Duration naturalLatency() {
        return NATURAL_LATENCY_MIN.plusMillis(ThreadLocalRandom.current().nextLong(NATURAL_LATENCY_SPREAD.toMillis() + 1));
    }
}
