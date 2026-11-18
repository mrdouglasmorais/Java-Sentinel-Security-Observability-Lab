package dev.sentinel.simulation;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * Latency and error model for a simulated dependency.
 *
 * <p>Latency is {@code baseLatency + uniform(0, jitter)}, plus {@code spikeLatency} with probability
 * {@code spikeProbability}. The spikes are what make the mean look healthy while P99 is terrible.
 */
public record FaultProfile(
        @NotNull @DurationMin(millis = 0) @DurationMax(seconds = 10) Duration baseLatency,
        @NotNull @DurationMin(millis = 0) @DurationMax(seconds = 10) Duration jitter,
        @DecimalMin("0.0") @DecimalMax("1.0") double spikeProbability,
        @NotNull @DurationMin(millis = 0) @DurationMax(seconds = 10) Duration spikeLatency,
        @DecimalMin("0.0") @DecimalMax("1.0") double errorRate) {

    public static final FaultProfile NONE = new FaultProfile(Duration.ZERO, Duration.ZERO, 0, Duration.ZERO, 0);

    public boolean active() {
        return !equals(NONE);
    }

    Duration sampleLatency(ThreadLocalRandom random) {
        long millis = baseLatency.toMillis();
        if (!jitter.isZero()) {
            millis += random.nextLong(jitter.toMillis() + 1);
        }
        if (spikeProbability > 0 && random.nextDouble() < spikeProbability) {
            millis += spikeLatency.toMillis();
        }
        return Duration.ofMillis(millis);
    }

    boolean sampleFailure(ThreadLocalRandom random) {
        return errorRate > 0 && random.nextDouble() < errorRate;
    }
}
