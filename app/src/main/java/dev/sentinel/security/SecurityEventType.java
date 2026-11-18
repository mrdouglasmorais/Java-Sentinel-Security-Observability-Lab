package dev.sentinel.security;

/**
 * Closed set of security events. Used as a metric tag, so it must stay small and bounded.
 */
public enum SecurityEventType {
    /** Missing, expired, malformed or wrongly signed token. */
    AUTHENTICATION_FAILED("authentication_failed"),
    /** Authenticated caller lacks the role required by the endpoint. */
    ACCESS_DENIED("access_denied"),
    /** Authenticated caller requested a resource owned by someone else (possible IDOR/BOLA probing). */
    OWNERSHIP_VIOLATION("ownership_violation"),
    /** Request rejected by the per-client rate limiter. */
    RATE_LIMITED("rate_limited"),
    /** A lab operator changed the fault injection configuration. */
    LAB_FAULT_CHANGED("lab_fault_changed");

    private final String tagValue;

    SecurityEventType(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
