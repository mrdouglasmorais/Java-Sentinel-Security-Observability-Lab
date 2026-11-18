package dev.sentinel.payments.risk;

/**
 * Raw, unprotected call to the external risk engine. Callers must not use it directly: go through
 * {@link RiskAssessmentPort}, which adds timeout, retry, circuit breaker and bulkhead.
 */
public interface RiskEngineClient {

    /**
     * @return a risk score between 0 (safe) and 100 (fraudulent)
     * @throws RiskEngineException when the dependency fails
     */
    int score(RiskRequest request);

    final class RiskEngineException extends RuntimeException {
        public RiskEngineException(String message) {
            super(message);
        }
    }
}
