package dev.sentinel.payments.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    Optional<Account> findById(UUID id);

    List<Account> findByOwner(String ownerId);

    /**
     * Locks the given accounts ({@code SELECT ... FOR NO KEY UPDATE}) in a deterministic order to avoid deadlocks between
     * concurrent transfers in opposite directions. Must run inside a transaction.
     */
    List<Account> lockAll(UUID first, UUID second);

    void updateBalance(UUID accountId, BigDecimal newBalance);

    List<LedgerEntry> ledger(UUID accountId, int limit);
}
