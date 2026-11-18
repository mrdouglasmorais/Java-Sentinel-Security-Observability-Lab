package dev.sentinel.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import dev.sentinel.payments.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskPolicyTest {

    private final RiskPolicy policy = new RiskPolicy(80, new BigDecimal("100.00"));

    @ParameterizedTest
    @CsvSource({ "0, COMPLETED", "79, COMPLETED", "80, REJECTED", "100, REJECTED" })
    void scoresAreComparedAgainstTheApprovalThreshold(int score, TransferStatus expected) {
        var decision = policy.decide(new RiskAssessment.Scored(score), new BigDecimal("50.00"));

        assertThat(decision.status()).isEqualTo(expected);
        assertThat(decision.riskScore()).isEqualTo(score);
    }

    @Test
    void lowValueTransfersFailOpenWhenTheRiskEngineIsUnavailable() {
        var decision = policy.decide(new RiskAssessment.Unavailable("circuit_open"), new BigDecimal("100.00"));

        assertThat(decision.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(decision.riskScore()).isNull();
        assertThat(decision.reason()).isEqualTo("fallback_low_value:circuit_open");
    }

    @Test
    void highValueTransfersFailClosedWhenTheRiskEngineIsUnavailable() {
        var decision = policy.decide(new RiskAssessment.Unavailable("timeout"), new BigDecimal("100.01"));

        assertThat(decision.status()).isEqualTo(TransferStatus.PENDING_REVIEW);
        assertThat(decision.reason()).isEqualTo("fallback_manual_review:timeout");
    }
}
