package dev.sentinel.payments.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import dev.sentinel.identity.CurrentUserProvider;
import dev.sentinel.payments.application.AccountService;
import dev.sentinel.payments.domain.Account;
import dev.sentinel.payments.domain.LedgerEntry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;
    private final CurrentUserProvider currentUser;

    public AccountController(AccountService accounts, CurrentUserProvider currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AccountResponse> myAccounts() {
        return accounts.myAccounts(currentUser.current()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountId}")
    public AccountResponse account(@PathVariable UUID accountId) {
        return AccountResponse.from(accounts.visibleAccount(currentUser.current(), accountId));
    }

    @GetMapping("/{accountId}/ledger")
    public List<LedgerEntryResponse> ledger(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return accounts.ledger(currentUser.current(), accountId, limit).stream().map(LedgerEntryResponse::from).toList();
    }

    public record AccountResponse(UUID id, String holderName, String currency, BigDecimal balance) {
        static AccountResponse from(Account account) {
            return new AccountResponse(account.id(), account.holderName(), account.currency(), account.balance());
        }
    }

    public record LedgerEntryResponse(UUID transferId, String direction, BigDecimal amount, BigDecimal balanceAfter,
            Instant createdAt) {
        static LedgerEntryResponse from(LedgerEntry entry) {
            return new LedgerEntryResponse(entry.transferId(), entry.direction().name(), entry.amount(),
                    entry.balanceAfter(), entry.createdAt());
        }
    }
}
