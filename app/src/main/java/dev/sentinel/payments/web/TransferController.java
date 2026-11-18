package dev.sentinel.payments.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import dev.sentinel.identity.CurrentUserProvider;
import dev.sentinel.payments.application.TransferCommand;
import dev.sentinel.payments.application.TransferService;
import dev.sentinel.payments.domain.Transfer;
import dev.sentinel.shared.web.Problems;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transfers;
    private final CurrentUserProvider currentUser;

    public TransferController(TransferService transfers, CurrentUserProvider currentUser) {
        this.transfers = transfers;
        this.currentUser = currentUser;
    }

    /**
     * {@code 201} completed, {@code 202} accepted for manual review, {@code 422} rejected by risk. Replaying the same
     * {@code Idempotency-Key} returns the original outcome without moving funds again.
     */
    @PostMapping
    public ResponseEntity<?> transfer(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        Transfer transfer = transfers.transfer(currentUser.current(),
                new TransferCommand(idempotencyKey, request.sourceAccountId(), request.targetAccountId(), request.amount()));
        URI location = URI.create("/api/transfers/" + transfer.id());
        return switch (transfer.status()) {
            case COMPLETED -> ResponseEntity.created(location).body(TransferResponse.from(transfer));
            case PENDING_REVIEW -> ResponseEntity.accepted().location(location).body(TransferResponse.from(transfer));
            case REJECTED -> {
                ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "transfer-rejected",
                        "Transfer rejected", "The transfer was rejected by risk assessment.");
                problem.setProperty("transfer_id", transfer.id());
                yield ResponseEntity.of(problem).location(location).build();
            }
        };
    }

    @GetMapping("/{transferId}")
    public TransferResponse transfer(@PathVariable UUID transferId) {
        return TransferResponse.from(transfers.visibleTransfer(currentUser.current(), transferId));
    }

    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2) BigDecimal amount) {
    }

    public record TransferResponse(UUID id, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount, String currency,
            String status, Integer riskScore, String decision, Instant createdAt) {
        static TransferResponse from(Transfer transfer) {
            return new TransferResponse(transfer.id(), transfer.sourceAccountId(), transfer.targetAccountId(),
                    transfer.amount(), transfer.currency(), transfer.status().name(), transfer.riskScore(),
                    transfer.decisionReason(), transfer.createdAt());
        }
    }
}
