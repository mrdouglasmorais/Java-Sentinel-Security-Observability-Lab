package dev.sentinel.payments.risk;

import java.math.BigDecimal;

import dev.sentinel.payments.domain.TransferStatus;

/**
 * Turns a risk assessment into a business decision, including the degradation policy when the risk engine is down:
 * low-value transfers keep flowing (fail open, bounded loss), everything else waits for manual review (fail closed).
 *
 * @param approvalThreshold    scores at or above this value are rejected
 * @param fallbackApprovalLimit maximum amount auto-approved while the risk engine is unavailable
 */
public record RiskPolicy(int approvalThreshold, BigDecimal fallbackApprovalLimit) {

    public Decision decide(RiskAssessment assessment, BigDecimal amount) {
        return switch (assessment) {
            case RiskAssessment.Scored(int score) when score >= approvalThreshold ->
                    new Decision(TransferStatus.REJECTED, score, "risk_score_above_threshold");
            case RiskAssessment.Scored(int score) ->
                    new Decision(TransferStatus.COMPLETED, score, "risk_approved");
            case RiskAssessment.Unavailable(String cause) when amount.compareTo(fallbackApprovalLimit) <= 0 ->
                    new Decision(TransferStatus.COMPLETED, null, "fallback_low_value:" + cause);
            case RiskAssessment.Unavailable(String cause) ->
                    new Decision(TransferStatus.PENDING_REVIEW, null, "fallback_manual_review:" + cause);
        };
    }

    public record Decision(TransferStatus status, Integer riskScore, String reason) {
    }
}
