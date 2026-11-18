package dev.sentinel.payments.risk;

/**
 * Outcome of asking the risk engine, after resilience policies were applied.
 */
public sealed interface RiskAssessment {

    record Scored(int score) implements RiskAssessment {
    }

    /**
     * @param cause bounded, low-cardinality reason: {@code timeout}, {@code circuit_open}, {@code bulkhead_full} or {@code error}
     */
    record Unavailable(String cause) implements RiskAssessment {
    }
}
