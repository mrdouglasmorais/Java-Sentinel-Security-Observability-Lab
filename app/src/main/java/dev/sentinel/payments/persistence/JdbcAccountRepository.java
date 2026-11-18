package dev.sentinel.payments.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.sentinel.payments.domain.Account;
import dev.sentinel.payments.domain.AccountRepository;
import dev.sentinel.payments.domain.LedgerEntry;
import dev.sentinel.payments.domain.PaymentException;
import dev.sentinel.simulation.FaultInjector;
import dev.sentinel.simulation.FaultTarget;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMNS = "id, owner_id, holder_name, currency, balance";

    private final JdbcClient jdbc;
    private final FaultInjector faults;

    JdbcAccountRepository(JdbcClient jdbc, FaultInjector faults) {
        this.jdbc = jdbc;
        this.faults = faults;
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE id = :id")
                .param("id", id)
                .query(JdbcAccountRepository::mapAccount)
                .optional();
    }

    @Override
    public List<Account> findByOwner(String ownerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE owner_id = :owner ORDER BY created_at")
                .param("owner", ownerId)
                .query(JdbcAccountRepository::mapAccount)
                .list();
    }

    @Override
    public List<Account> lockAll(UUID first, UUID second) {
        // NO KEY UPDATE: balances change, keys never do, so foreign-key checks from other transactions are not blocked.
        List<Account> locked = jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE id IN (:first, :second) ORDER BY id FOR NO KEY UPDATE")
                .param("first", first)
                .param("second", second)
                .query(JdbcAccountRepository::mapAccount)
                .list();
        simulateSlowStatementWhileLocked();
        return locked;
    }

    @Override
    public void updateBalance(UUID accountId, BigDecimal newBalance) {
        int updated = jdbc.sql("UPDATE account SET balance = :balance, version = version + 1 WHERE id = :id")
                .param("balance", newBalance)
                .param("id", accountId)
                .update();
        if (updated != 1) {
            throw new PaymentException.AccountNotFound(accountId);
        }
    }

    @Override
    public List<LedgerEntry> ledger(UUID accountId, int limit) {
        return jdbc.sql("""
                        SELECT id, transfer_id, account_id, direction, amount, balance_after, created_at
                        FROM ledger_entry
                        WHERE account_id = :account
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("account", accountId)
                .param("limit", limit)
                .query((rs, _) -> new LedgerEntry(rs.getLong("id"), rs.getObject("transfer_id", UUID.class),
                        rs.getObject("account_id", UUID.class), LedgerEntry.Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * Lab fault: a real, slow SQL statement executed while the account rows are locked. It shows up as a long
     * database span, causes lock waits for concurrent transfers and drains the connection pool.
     */
    private void simulateSlowStatementWhileLocked() {
        Duration delay = faults.sampleLatency(FaultTarget.DATABASE);
        if (!delay.isZero()) {
            jdbc.sql("SELECT pg_sleep(:seconds)")
                    .param("seconds", delay.toMillis() / 1000.0)
                    .query((rs, _) -> rs.getObject(1))
                    .list();
        }
    }

    private static Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("owner_id"), rs.getString("holder_name"),
                rs.getString("currency"), rs.getBigDecimal("balance"));
    }
}
