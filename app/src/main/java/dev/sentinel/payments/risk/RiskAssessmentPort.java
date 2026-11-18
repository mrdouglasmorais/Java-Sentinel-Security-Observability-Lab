package dev.sentinel.payments.risk;

public interface RiskAssessmentPort {

    /**
     * Never throws because of dependency failures; those are reported as {@link RiskAssessment.Unavailable}.
     */
    RiskAssessment assess(RiskRequest request);
}
