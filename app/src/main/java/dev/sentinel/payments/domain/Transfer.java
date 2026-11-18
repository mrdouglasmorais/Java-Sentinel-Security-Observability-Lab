package dev.sentinel.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import dev.sentinel.identity.CurrentUser;
import dev.sentinel.identity.Role;

public record Transfer(
        UUID id,
        String requestedBy,
        String idempotencyKey,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        Integer riskScore,
        String decisionReason,
        Instant createdAt) {

    public boolean isVisibleTo(CurrentUser user) {
        return requestedBy.equals(user.username()) || user.has(Role.AUDITOR);
    }

    /**
     * An idempotent replay must carry the same payload; reusing a key for a different transfer is a client bug
     * (or an attack) and must not silently return the original result.
     */
    public boolean matches(UUID source, UUID target, BigDecimal requestedAmount) {
        return sourceAccountId.equals(source) && targetAccountId.equals(target) && amount.compareTo(requestedAmount) == 0;
    }
}
