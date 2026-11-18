package dev.sentinel.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import dev.sentinel.payments.risk.RiskAssessment;
import dev.sentinel.payments.risk.RiskAssessmentPort;
import dev.sentinel.payments.risk.RiskEngineClient;
import dev.sentinel.payments.risk.RiskRequest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Protects transfers from the risk engine. Decorator order, outermost first:
 * <pre>
 * Retry -> CircuitBreaker -> Bulkhead -> TimeLimiter -> call
 * </pre>
 * <ul>
 *   <li><b>TimeLimiter</b> bounds every attempt; without it a slow dependency holds the caller indefinitely.</li>
 *   <li><b>Bulkhead</b> caps concurrent calls. With virtual threads, thread pools no longer provide back-pressure,
 *   so this is the limit that keeps a slow dependency from absorbing unbounded concurrency.</li>
 *   <li><b>CircuitBreaker</b> sees every attempt (including slow ones) and stops calling a dependency that is
 *   clearly unhealthy, so retries do not hammer it.</li>
 *   <li><b>Retry</b> uses exponential backoff with randomized jitter so synchronized clients do not retry in waves.
 *   It never retries an open circuit or a full bulkhead.</li>
 * </ul>
 * All limits are configured in {@code application.yaml} under {@code resilience4j.*.instances.riskEngine}.
 */
@Component
public class ResilientRiskAssessment implements RiskAssessmentPort {

    static final String INSTANCE = "riskEngine";
    private static final Logger log = LoggerFactory.getLogger(ResilientRiskAssessment.class);

    private final RiskEngineClient client;
    private final ExecutorService executor;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final Tracer tracer;
    private final MeterRegistry registry;
    private final Counter attemptsCounter;
    private final ConcurrentMap<String, Timer> assessmentTimers = new ConcurrentHashMap<>();

    public ResilientRiskAssessment(RiskEngineClient client, RetryRegistry retries, CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads, TimeLimiterRegistry timeLimiters, Tracer tracer, MeterRegistry registry) {
        this.client = client;
        // The attempt runs on a virtual thread; this keeps the caller's trace context on that thread.
        this.executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
        this.retry = retries.retry(INSTANCE);
        this.circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
        this.bulkhead = bulkheads.bulkhead(INSTANCE);
        this.timeLimiter = timeLimiters.timeLimiter(INSTANCE);
        this.tracer = tracer;
        this.registry = registry;
        this.attemptsCounter = Counter.builder("sentinel.risk.attempts")
                .description("Attempts made against the risk engine; compare with assessments to see retry amplification")
                .register(registry);
    }

    @Override
    public RiskAssessment assess(RiskRequest request) {
        Span span = tracer.spanBuilder("risk assessment").startSpan();
        AtomicInteger attempts = new AtomicInteger();
        Timer.Sample sample = Timer.start(registry);
        RiskAssessment result;
        try (Scope _ = span.makeCurrent()) {
            Callable<Integer> attempt = () -> {
                attempts.incrementAndGet();
                return timeLimiter.executeFutureSupplier(() -> executor.submit(() -> client.score(request)));
            };
            Callable<Integer> guarded = Retry.decorateCallable(retry,
                    CircuitBreaker.decorateCallable(circuitBreaker,
                            Bulkhead.decorateCallable(bulkhead, attempt)));
            result = new RiskAssessment.Scored(guarded.call());
        } catch (Exception failure) {
            if (interrupted(failure)) {
                Thread.currentThread().interrupt();
            }
            String cause = causeOf(failure);
            result = new RiskAssessment.Unavailable(cause);
            log.atWarn()
                    .addKeyValue("risk.unavailable_cause", cause)
                    .addKeyValue("risk.attempts", attempts.get())
                    .addKeyValue("circuit_breaker.state", circuitBreaker.getState().name())
                    .log("risk engine unavailable, applying degradation policy");
        }

        String outcome = switch (result) {
            case RiskAssessment.Scored _ -> "scored";
            case RiskAssessment.Unavailable(String cause) -> cause;
        };
        if (result instanceof RiskAssessment.Unavailable) {
            span.setStatus(StatusCode.ERROR, outcome);
        }
        span.setAttribute("sentinel.risk.outcome", outcome);
        span.setAttribute("sentinel.risk.attempts", attempts.get());
        span.setAttribute("sentinel.circuit_breaker.state", circuitBreaker.getState().name());
        span.end();

        sample.stop(assessmentTimers.computeIfAbsent(outcome, key -> Timer.builder("sentinel.risk.assessment")
                .description("End-to-end risk assessment including retries, as perceived by the transfer")
                .tag("outcome", key)
                .publishPercentileHistogram()
                .register(registry)));
        attemptsCounter.increment(attempts.get());
        return result;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }

    private static String causeOf(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) {
                return "timeout";
            }
            if (current instanceof CallNotPermittedException) {
                return "circuit_open";
            }
            if (current instanceof BulkheadFullException) {
                return "bulkhead_full";
            }
        }
        return "error";
    }

    private static boolean interrupted(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
