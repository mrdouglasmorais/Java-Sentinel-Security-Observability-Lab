package dev.sentinel.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(long id, UUID transferId, UUID accountId, Direction direction, BigDecimal amount,
        BigDecimal balanceAfter, Instant createdAt) {

    public enum Direction {
        DEBIT, CREDIT
    }
}
