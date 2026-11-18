package dev.sentinel.simulation;

import java.time.Duration;
import java.util.Map;

/**
 * Named, reproducible incidents. Each maps to a documented walkthrough in {@code docs/scenarios}.
 */
public enum LabScenario {

    /** Risk engine becomes slow and erratic: P50 barely moves, P99 explodes, timeouts trigger retries. */
    LATENCY_JITTER(Map.of(FaultTarget.RISK_ENGINE,
            new FaultProfile(Duration.ofMillis(80), Duration.ofMillis(220), 0.08, Duration.ofMillis(1200), 0.0))),

    /** Risk engine fails most calls: the circuit breaker opens and the degradation policy takes over. */
    DEPENDENCY_OUTAGE(Map.of(FaultTarget.RISK_ENGINE,
            new FaultProfile(Duration.ofMillis(30), Duration.ofMillis(20), 0.0, Duration.ZERO, 0.9))),

    /** The funds-moving transaction slows down while holding row locks: connection pool saturation follows. */
    SLOW_DATABASE(Map.of(FaultTarget.DATABASE,
            new FaultProfile(Duration.ofMillis(250), Duration.ofMillis(250), 0.05, Duration.ofMillis(1500), 0.0)));

    private final Map<FaultTarget, FaultProfile> profiles;

    LabScenario(Map<FaultTarget, FaultProfile> profiles) {
        this.profiles = profiles;
    }

    public Map<FaultTarget, FaultProfile> profiles() {
        return profiles;
    }

    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
