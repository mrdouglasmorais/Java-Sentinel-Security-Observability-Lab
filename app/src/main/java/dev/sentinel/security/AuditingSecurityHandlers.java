package dev.sentinel.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import dev.sentinel.shared.web.ProblemResponseWriter;
import dev.sentinel.shared.web.Problems;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401/403 handlers that keep the RFC 6750 {@code WWW-Authenticate} header, answer with problem details and record a
 * security event. The response deliberately does not say why a token was rejected; the audit log does.
 */
@Component
public class AuditingSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final SecurityAuditLog auditLog;
    private final ProblemResponseWriter problemWriter;

    public AuditingSecurityHandlers(SecurityAuditLog auditLog, ProblemResponseWriter problemWriter) {
        this.auditLog = auditLog;
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        bearerEntryPoint.commence(request, response, exception);
        auditLog.record(SecurityEventType.AUTHENTICATION_FAILED, null, request.getRemoteAddr(), request.getRequestURI(),
                Map.of("auth.failure", exception.getClass().getSimpleName()));
        problemWriter.write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated",
                "Authentication required", "A valid bearer token is required."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        bearerAccessDeniedHandler.handle(request, response, exception);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        auditLog.record(SecurityEventType.ACCESS_DENIED, authentication == null ? null : authentication.getName(),
                request.getRemoteAddr(), request.getRequestURI(), Map.of("http.request.method", request.getMethod()));
        problemWriter.write(request, response, Problems.of(HttpStatus.FORBIDDEN, "forbidden",
                "Access denied", "You are not allowed to perform this operation."));
    }
}
