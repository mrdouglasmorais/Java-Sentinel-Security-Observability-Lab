package dev.sentinel.payments.application;

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

    public PaymentsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void transferProcessed(Transfer transfer) {
        Counter.builder("sentinel.transfers")
                .description("Transfers processed, by final status and decision path")
                .tag("status", transfer.status().name().toLowerCase())
                .tag("decision", decisionPath(transfer.decisionReason()))
                .register(registry)
                .increment();
    }

    public void idempotentReplay() {
        Counter.builder("sentinel.transfers.idempotent.replays")
                .description("Requests answered from a previously stored transfer")
                .register(registry)
                .increment();
    }

    private static String decisionPath(String reason) {
        int separator = reason.indexOf(':');
        return separator < 0 ? reason : reason.substring(0, separator);
    }
}
