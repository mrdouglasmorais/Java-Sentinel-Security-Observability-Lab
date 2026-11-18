package dev.sentinel.payments.domain;

import java.util.UUID;

/**
 * Business failures of the payments module. Sealed so the web layer maps every case exhaustively.
 */
public sealed abstract class PaymentException extends RuntimeException {

    protected PaymentException(String message) {
        super(message);
    }

    /**
     * Also thrown when the account exists but belongs to someone else, so callers cannot enumerate account ids.
     */
    public static final class AccountNotFound extends PaymentException {
        public AccountNotFound(UUID accountId) {
            super("Account " + accountId + " not found");
        }
    }

    public static final class TransferNotFound extends PaymentException {
        public TransferNotFound(UUID transferId) {
            super("Transfer " + transferId + " not found");
        }
    }

    public static final class InsufficientFunds extends PaymentException {
        public InsufficientFunds(UUID accountId) {
            super("Insufficient funds in account " + accountId);
        }
    }

    public static final class InvalidTransfer extends PaymentException {
        public InvalidTransfer(String reason) {
            super(reason);
        }
    }

    public static final class IdempotencyConflict extends PaymentException {
        public IdempotencyConflict(String idempotencyKey) {
            super("Idempotency key " + idempotencyKey + " was already used for a different transfer");
        }
    }
}
