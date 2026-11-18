package dev.sentinel.payments.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.sentinel.identity.CurrentUser;
import dev.sentinel.payments.domain.Account;
import dev.sentinel.payments.domain.AccountRepository;
import dev.sentinel.payments.domain.LedgerEntry;
import dev.sentinel.payments.domain.PaymentException;
import dev.sentinel.security.SecurityAuditLog;
import dev.sentinel.security.SecurityEventType;

import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final SecurityAuditLog auditLog;

    public AccountService(AccountRepository accounts, SecurityAuditLog auditLog) {
        this.accounts = accounts;
        this.auditLog = auditLog;
    }

    public List<Account> myAccounts(CurrentUser user) {
        return accounts.findByOwner(user.username());
    }

    public Account visibleAccount(CurrentUser user, UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new PaymentException.AccountNotFound(accountId));
        if (!account.isVisibleTo(user)) {
            auditLog.recordForCurrentRequest(SecurityEventType.OWNERSHIP_VIOLATION, user.username(),
                    Map.of("resource.type", "account", "resource.id", accountId.toString()));
            throw new PaymentException.AccountNotFound(accountId);
        }
        return account;
    }

    public List<LedgerEntry> ledger(CurrentUser user, UUID accountId, int limit) {
        visibleAccount(user, accountId);
        return accounts.ledger(accountId, limit);
    }
}
