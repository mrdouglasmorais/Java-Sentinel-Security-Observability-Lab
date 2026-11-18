package dev.sentinel.simulation;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Holds the active fault profile per target. Always present (no-op by default) so production code paths are the same
 * with and without the lab; only the control endpoint is conditional.
 *
 * <p>Exposes {@code sentinel_lab_fault_active{target}} so dashboards can annotate exactly when a fault was injected,
 * which keeps the investigation honest: the signal must be found in telemetry, but the ground truth is recorded.
 */
@Component
public class FaultInjector {

    private final Map<FaultTarget, FaultProfile> profiles = new ConcurrentHashMap<>();

    public FaultInjector(MeterRegistry registry) {
        for (FaultTarget target : FaultTarget.values()) {
            profiles.put(target, FaultProfile.NONE);
            Gauge.builder("sentinel.lab.fault.active", () -> profile(target).active() ? 1 : 0)
                    .description("1 while a lab fault is injected on the target")
                    .tag("target", target.name().toLowerCase())
                    .register(registry);
        }
    }

    public FaultProfile profile(FaultTarget target) {
        return profiles.get(target);
    }

    public Map<FaultTarget, FaultProfile> all() {
        return new EnumMap<>(profiles);
    }

    public void apply(FaultTarget target, FaultProfile profile) {
        profiles.put(target, profile);
    }

    public void reset() {
        profiles.replaceAll((_, _) -> FaultProfile.NONE);
    }

    public Duration sampleLatency(FaultTarget target) {
        return profile(target).sampleLatency(ThreadLocalRandom.current());
    }

    public boolean sampleFailure(FaultTarget target) {
        return profile(target).sampleFailure(ThreadLocalRandom.current());
    }
}
