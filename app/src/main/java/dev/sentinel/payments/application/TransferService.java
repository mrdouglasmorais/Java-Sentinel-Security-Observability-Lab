package dev.sentinel.payments.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.sentinel.identity.CurrentUser;
import dev.sentinel.payments.domain.Account;
import dev.sentinel.payments.domain.AccountRepository;
import dev.sentinel.payments.domain.LedgerEntry;
import dev.sentinel.payments.domain.PaymentException;
import dev.sentinel.payments.domain.Transfer;
import dev.sentinel.payments.domain.TransferRepository;
import dev.sentinel.payments.domain.TransferStatus;
import dev.sentinel.payments.risk.RiskAssessment;
import dev.sentinel.payments.risk.RiskAssessmentPort;
import dev.sentinel.payments.risk.RiskPolicy;
import dev.sentinel.payments.risk.RiskRequest;
import dev.sentinel.security.SecurityAuditLog;
import dev.sentinel.security.SecurityEventType;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes transfers. The risk engine is called <em>before</em> the database transaction starts: holding a pooled
 * connection and row locks while waiting on a slow remote dependency is a classic way to turn dependency latency into
 * connection pool exhaustion.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final RiskAssessmentPort riskAssessment;
    private final RiskPolicy riskPolicy;
    private final TransactionTemplate transaction;
    private final PaymentsMetrics metrics;
    private final SecurityAuditLog auditLog;

    public TransferService(AccountRepository accounts, TransferRepository transfers, RiskAssessmentPort riskAssessment,
            PaymentsProperties properties, TransactionTemplate transaction, PaymentsMetrics metrics, SecurityAuditLog auditLog) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.riskAssessment = riskAssessment;
        this.riskPolicy = properties.riskPolicy();
        this.transaction = transaction;
        this.metrics = metrics;
        this.auditLog = auditLog;
    }

    @WithSpan("transfer execute")
    public Transfer transfer(CurrentUser user, TransferCommand command) {
        var existing = transfers.findByIdempotencyKey(user.username(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        if (command.sourceAccountId().equals(command.targetAccountId())) {
            throw new PaymentException.InvalidTransfer("Source and target accounts must differ");
        }

        Account source = ownedAccount(user, command.sourceAccountId());
        Account target = accounts.findById(command.targetAccountId())
                .orElseThrow(() -> new PaymentException.AccountNotFound(command.targetAccountId()));
        if (!source.currency().equals(target.currency())) {
            throw new PaymentException.InvalidTransfer("Cross-currency transfers are not supported");
        }
        if (source.balance().compareTo(command.amount()) < 0) {
            throw new PaymentException.InsufficientFunds(source.id());
        }

        RiskAssessment assessment = riskAssessment.assess(
                new RiskRequest(user.username(), source.id(), target.id(), command.amount()));
        RiskPolicy.Decision decision = riskPolicy.decide(assessment, command.amount());

        Transfer transfer;
        try {
            transfer = transaction.execute(_ -> persist(user, command, source.currency(), decision));
        } catch (DuplicateKeyException concurrentRequestWithSameKey) {
            return transfers.findByIdempotencyKey(user.username(), command.idempotencyKey())
                    .map(stored -> replay(stored, command))
                    .orElseThrow(() -> concurrentRequestWithSameKey);
        }

        Span.current()
                .setAttribute("sentinel.transfer.status", transfer.status().name())
                .setAttribute("sentinel.transfer.decision", decision.reason());
        metrics.transferProcessed(transfer);
        return transfer;
    }

    public Transfer visibleTransfer(CurrentUser user, UUID transferId) {
        Transfer transfer = transfers.findById(transferId).orElseThrow(() -> new PaymentException.TransferNotFound(transferId));
        if (!transfer.isVisibleTo(user)) {
            auditLog.recordForCurrentRequest(SecurityEventType.OWNERSHIP_VIOLATION, user.username(),
                    Map.of("resource.type", "transfer", "resource.id", transferId.toString()));
            throw new PaymentException.TransferNotFound(transferId);
        }
        return transfer;
    }

    private Account ownedAccount(CurrentUser user, UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new PaymentException.AccountNotFound(accountId));
        if (!account.isOwnedBy(user)) {
            auditLog.recordForCurrentRequest(SecurityEventType.OWNERSHIP_VIOLATION, user.username(),
                    Map.of("resource.type", "account", "resource.id", accountId.toString(), "operation", "transfer"));
            throw new PaymentException.AccountNotFound(accountId);
        }
        return account;
    }

    /**
     * Lock order matters. Account rows are locked <em>before</em> inserting the transfer: the transfer's foreign keys
     * take {@code KEY SHARE} locks on both accounts, and taking those first lets two opposite transfers deadlock
     * while upgrading to row locks (found by the lab under load, see docs/scenarios/00-deadlock-found-under-load.md).
     */
    private Transfer persist(CurrentUser user, TransferCommand command, String currency, RiskPolicy.Decision decision) {
        Transfer pending = new Transfer(null, user.username(), command.idempotencyKey(), command.sourceAccountId(),
                command.targetAccountId(), command.amount(), currency, decision.status(), decision.riskScore(),
                decision.reason(), null);
        if (decision.status() != TransferStatus.COMPLETED) {
            return transfers.insert(pending);
        }

        Map<UUID, Account> locked = accounts.lockAll(command.sourceAccountId(), command.targetAccountId()).stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        Account source = locked.get(command.sourceAccountId());
        Account target = locked.get(command.targetAccountId());

        // Re-checked under lock: the early check above is only an optimisation.
        BigDecimal sourceBalance = source.balance().subtract(command.amount());
        if (sourceBalance.signum() < 0) {
            throw new PaymentException.InsufficientFunds(source.id());
        }
        BigDecimal targetBalance = target.balance().add(command.amount());

        Transfer transfer = transfers.insert(pending);
        accounts.updateBalance(source.id(), sourceBalance);
        accounts.updateBalance(target.id(), targetBalance);
        transfers.insertLedgerEntry(transfer.id(), source.id(), LedgerEntry.Direction.DEBIT, transfer.amount(), sourceBalance);
        transfers.insertLedgerEntry(transfer.id(), target.id(), LedgerEntry.Direction.CREDIT, transfer.amount(), targetBalance);
        return transfer;
    }

    private Transfer replay(Transfer stored, TransferCommand command) {
        if (!stored.matches(command.sourceAccountId(), command.targetAccountId(), command.amount())) {
            throw new PaymentException.IdempotencyConflict(command.idempotencyKey());
        }
        metrics.idempotentReplay();
        return stored;
    }
}
