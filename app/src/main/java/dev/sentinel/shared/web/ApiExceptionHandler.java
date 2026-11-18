package dev.sentinel.shared.web;

import java.util.Set;

import dev.sentinel.payments.domain.PaymentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem details. Unexpected errors return a generic message with the {@code trace_id};
 * the stack trace goes to the logs (and to the span), never to the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final Set<String> POSTGRES_TIMEOUT_STATES = Set.of("55P03", "57014");

    @ExceptionHandler(PaymentException.class)
    ProblemDetail paymentFailure(PaymentException exception) {
        return switch (exception) {
            case PaymentException.AccountNotFound e -> Problems.of(HttpStatus.NOT_FOUND, "account-not-found", "Account not found", e.getMessage());
            case PaymentException.TransferNotFound e -> Problems.of(HttpStatus.NOT_FOUND, "transfer-not-found", "Transfer not found", e.getMessage());
            case PaymentException.InsufficientFunds e -> Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "Insufficient funds", e.getMessage());
            case PaymentException.InvalidTransfer e -> Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-transfer", "Invalid transfer", e.getMessage());
            case PaymentException.IdempotencyConflict e -> Problems.of(HttpStatus.CONFLICT, "idempotency-conflict", "Idempotency key reused", e.getMessage());
        };
    }

    /**
     * Method-security denials must reach Spring Security's handlers (403 + audit event), not the catch-all below.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void securityFailure(RuntimeException exception) {
        throw exception;
    }

    /**
     * Pool exhaustion and lock timeouts mean "overloaded, try later", not "bug": answer 503 so clients and load
     * balancers back off instead of treating it as a hard failure.
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class})
    ResponseEntity<ProblemDetail> overloaded(Exception exception) {
        return serviceUnavailable(exception);
    }

    /**
     * Spring leaves PostgreSQL's {@code lock_timeout} (55P03) and {@code statement_timeout} (57014) uncategorized.
     */
    @ExceptionHandler(UncategorizedSQLException.class)
    ResponseEntity<ProblemDetail> uncategorizedSql(UncategorizedSQLException exception) {
        String sqlState = exception.getSQLException() == null ? null : exception.getSQLException().getSQLState();
        if (POSTGRES_TIMEOUT_STATES.contains(sqlState)) {
            return serviceUnavailable(exception);
        }
        return ResponseEntity.internalServerError().body(unexpected(exception));
    }

    private ResponseEntity<ProblemDetail> serviceUnavailable(Exception exception) {
        log.warn("database unavailable or saturated: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable", "Temporarily unavailable",
                        "The service is saturated. Retry shortly."));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("unhandled exception", exception);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Unexpected error. Quote the trace_id when reporting it.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            Problems.withTraceId(problem);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
