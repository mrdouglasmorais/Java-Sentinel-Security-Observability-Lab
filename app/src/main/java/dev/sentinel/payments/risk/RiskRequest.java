package dev.sentinel.payments.risk;

import java.math.BigDecimal;
import java.util.UUID;

public record RiskRequest(String requestedBy, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {
}
