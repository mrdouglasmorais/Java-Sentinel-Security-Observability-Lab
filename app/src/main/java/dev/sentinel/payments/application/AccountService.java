package dev.sentinel.payments.application;

import java.util.List;
import java.util.UUID;

import dev.sentinel.identity.CurrentUser;
import dev.sentinel.payments.domain.Account;
import dev.sentinel.payments.domain.AccountRepository;
import dev.sentinel.payments.domain.LedgerEntry;
import dev.sentinel.payments.domain.PaymentException;

import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final ResourceGuard guard;

    public AccountService(AccountRepository accounts, ResourceGuard guard) {
        this.accounts = accounts;
        this.guard = guard;
    }

    public List<Account> myAccounts(CurrentUser user) {
        return accounts.findByOwner(user.username());
    }

    public Account visibleAccount(CurrentUser user, UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new PaymentException.AccountNotFound(accountId));
        return guard.require(user, account, account.isVisibleTo(user), "account", accountId.toString(),
                () -> new PaymentException.AccountNotFound(accountId));
    }

    public List<LedgerEntry> ledger(CurrentUser user, UUID accountId, int limit) {
        visibleAccount(user, accountId);
        return accounts.ledger(accountId, limit);
    }
}
