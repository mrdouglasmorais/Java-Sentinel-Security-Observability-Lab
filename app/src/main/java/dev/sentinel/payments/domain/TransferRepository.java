package dev.sentinel.payments.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {

    Optional<Transfer> findById(UUID id);

    Optional<Transfer> findByIdempotencyKey(String requestedBy, String idempotencyKey);

    /**
     * @throws org.springframework.dao.DuplicateKeyException when the idempotency key was taken concurrently
     */
    Transfer insert(Transfer transfer);

    void insertLedgerEntry(UUID transferId, UUID accountId, LedgerEntry.Direction direction, BigDecimal amount, BigDecimal balanceAfter);
}
