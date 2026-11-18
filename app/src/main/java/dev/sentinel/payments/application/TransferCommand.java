package dev.sentinel.payments.application;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCommand(String idempotencyKey, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {
}
