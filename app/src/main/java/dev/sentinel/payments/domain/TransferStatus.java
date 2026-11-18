package dev.sentinel.payments.domain;

public enum TransferStatus {
    /** Funds moved. */
    COMPLETED,
    /** Risk engine scored the transfer above the approval threshold. No funds moved. */
    REJECTED,
    /** Risk engine unavailable and amount above the auto-approval limit. No funds moved; needs manual review. */
    PENDING_REVIEW
}
