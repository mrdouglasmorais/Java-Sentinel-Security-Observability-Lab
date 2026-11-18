package dev.sentinel.security;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token-bucket limits per authenticated client. Transfers get a tighter budget than reads because they are the
 * expensive, abuse-prone operation.
 */
@Validated
@ConfigurationProperties("sentinel.security.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Valid @NotNull Policy api,
        @Valid @NotNull Policy transfers,
        @Min(100) int maxTrackedClients) {

    public record Policy(@Min(1) long capacity, @Min(1) long refillTokens, @NotNull Duration refillPeriod) {
    }
}
