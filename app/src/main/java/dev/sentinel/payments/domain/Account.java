package dev.sentinel.payments.domain;

import java.math.BigDecimal;
import java.util.UUID;

import dev.sentinel.identity.CurrentUser;
import dev.sentinel.identity.Role;

public record Account(UUID id, String ownerId, String holderName, String currency, BigDecimal balance) {

    public boolean isOwnedBy(CurrentUser user) {
        return ownerId.equals(user.username());
    }

    /**
     * Owners see their accounts; auditors have read-only visibility over all accounts.
     */
    public boolean isVisibleTo(CurrentUser user) {
        return isOwnedBy(user) || user.has(Role.AUDITOR);
    }
}
