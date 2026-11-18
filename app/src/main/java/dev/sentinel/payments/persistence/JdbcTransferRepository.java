package dev.sentinel.payments.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import dev.sentinel.payments.domain.LedgerEntry;
import dev.sentinel.payments.domain.Transfer;
import dev.sentinel.payments.domain.TransferRepository;
import dev.sentinel.payments.domain.TransferStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransferRepository implements TransferRepository {

    private static final String COLUMNS = """
            id, requested_by, idempotency_key, source_account_id, target_account_id, amount, currency, status,
            risk_score, decision_reason, created_at""";

    private final JdbcClient jdbc;

    JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfer WHERE id = :id")
                .param("id", id)
                .query(JdbcTransferRepository::mapTransfer)
                .optional();
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String requestedBy, String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfer WHERE requested_by = :requestedBy AND idempotency_key = :key")
                .param("requestedBy", requestedBy)
                .param("key", idempotencyKey)
                .query(JdbcTransferRepository::mapTransfer)
                .optional();
    }

    @Override
    public Transfer insert(Transfer transfer) {
        return jdbc.sql("""
                        INSERT INTO transfer (requested_by, idempotency_key, source_account_id, target_account_id, amount,
                                              currency, status, risk_score, decision_reason)
                        VALUES (:requestedBy, :key, :source, :target, :amount, :currency, :status, :riskScore, :reason)
                        RETURNING\s""" + COLUMNS)
                .param("requestedBy", transfer.requestedBy())
                .param("key", transfer.idempotencyKey())
                .param("source", transfer.sourceAccountId())
                .param("target", transfer.targetAccountId())
                .param("amount", transfer.amount())
                .param("currency", transfer.currency())
                .param("status", transfer.status().name())
                .param("riskScore", transfer.riskScore())
                .param("reason", transfer.decisionReason())
                .query(JdbcTransferRepository::mapTransfer)
                .single();
    }

    @Override
    public void insertLedgerEntry(UUID transferId, UUID accountId, LedgerEntry.Direction direction, BigDecimal amount,
            BigDecimal balanceAfter) {
        jdbc.sql("""
                        INSERT INTO ledger_entry (transfer_id, account_id, direction, amount, balance_after)
                        VALUES (:transfer, :account, :direction, :amount, :balanceAfter)
                        """)
                .param("transfer", transferId)
                .param("account", accountId)
                .param("direction", direction.name())
                .param("amount", amount)
                .param("balanceAfter", balanceAfter)
                .update();
    }

    private static Transfer mapTransfer(ResultSet rs, int rowNum) throws SQLException {
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getString("requested_by"),
                rs.getString("idempotency_key"),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("target_account_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getObject("risk_score", Integer.class),
                rs.getString("decision_reason"),
                rs.getTimestamp("created_at").toInstant());
    }
}
