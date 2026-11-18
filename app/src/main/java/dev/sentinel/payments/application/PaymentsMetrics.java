package dev.sentinel.payments.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.sentinel.payments.domain.Transfer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Business metrics. Amounts are not exported as metrics: they are sensitive and belong in the ledger, not in
 * Prometheus.
 */
@Component
public class PaymentsMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> processed = new ConcurrentHashMap<>();
    private final Counter replays;

    public PaymentsMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.replays = Counter.builder("sentinel.transfers.idempotent.replays")
                .description("Requests answered from a previously stored transfer")
                .register(registry);
    }

    public void transferProcessed(Transfer transfer) {
        String status = transfer.status().name().toLowerCase();
        String decision = decisionPath(transfer.decisionReason());
        processed.computeIfAbsent(status + '\u0000' + decision, key -> Counter.builder("sentinel.transfers")
                .description("Transfers processed, by final status and decision path")
                .tag("status", status)
                .tag("decision", decision)
                .register(registry))
                .increment();
    }

    public void idempotentReplay() {
        replays.increment();
    }

    private static String decisionPath(String reason) {
        int separator = reason.indexOf(':');
        return separator < 0 ? reason : reason.substring(0, separator);
    }
}
