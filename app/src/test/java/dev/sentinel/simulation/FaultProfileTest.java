package dev.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class FaultProfileTest {

    private final ThreadLocalRandom random = ThreadLocalRandom.current();

    @Test
    void noneIsInactiveAndAddsNothing() {
        assertThat(FaultProfile.NONE.active()).isFalse();
        assertThat(FaultProfile.NONE.sampleLatency(random)).isEqualTo(Duration.ZERO);
        assertThat(FaultProfile.NONE.sampleFailure(random)).isFalse();
    }

    @Test
    void latencyStaysWithinBaseAndJitterWithoutSpikes() {
        var profile = new FaultProfile(Duration.ofMillis(100), Duration.ofMillis(50), 0, Duration.ofSeconds(5), 0);

        IntStream.range(0, 1_000).forEach(_ ->
                assertThat(profile.sampleLatency(random)).isBetween(Duration.ofMillis(100), Duration.ofMillis(150)));
    }

    @Test
    void spikesProduceATailFarAboveTheBase() {
        var profile = new FaultProfile(Duration.ofMillis(10), Duration.ZERO, 0.5, Duration.ofSeconds(1), 0);

        long spikes = IntStream.range(0, 1_000)
                .mapToObj(_ -> profile.sampleLatency(random))
                .filter(latency -> latency.compareTo(Duration.ofSeconds(1)) > 0)
                .count();

        assertThat(spikes).isBetween(350L, 650L);
    }

    @Test
    void errorRateOfOneAlwaysFails() {
        var profile = new FaultProfile(Duration.ZERO, Duration.ZERO, 0, Duration.ZERO, 1.0);

        assertThat(profile.active()).isTrue();
        assertThat(IntStream.range(0, 100).allMatch(_ -> profile.sampleFailure(random))).isTrue();
    }
}
