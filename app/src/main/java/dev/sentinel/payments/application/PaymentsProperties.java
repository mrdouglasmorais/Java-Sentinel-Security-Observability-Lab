package dev.sentinel.payments.application;

import java.math.BigDecimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import dev.sentinel.payments.risk.RiskPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("sentinel.payments.risk")
public record PaymentsProperties(
        @Min(1) @Max(100) int approvalThreshold,
        @NotNull @PositiveOrZero BigDecimal fallbackApprovalLimit) {

    public RiskPolicy riskPolicy() {
        return new RiskPolicy(approvalThreshold, fallbackApprovalLimit);
    }
}
