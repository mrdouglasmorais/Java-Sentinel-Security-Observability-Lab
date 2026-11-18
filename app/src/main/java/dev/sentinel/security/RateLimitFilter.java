package dev.sentinel.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import dev.sentinel.shared.web.ProblemResponseWriter;
import dev.sentinel.shared.web.Problems;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client token bucket, applied after authentication so the key is the authenticated principal rather than a
 * spoofable header. Buckets live in a bounded Caffeine cache: an attacker rotating identities can evict entries but
 * cannot exhaust heap.
 *
 * <p>In-memory buckets are per instance. Running several replicas requires a distributed backend
 * (Bucket4j supports Redis/JCache) or rate limiting at the gateway.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final SecurityAuditLog auditLog;
    private final ProblemResponseWriter problemWriter;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(RateLimitProperties properties, SecurityAuditLog auditLog, ProblemResponseWriter problemWriter) {
        this.properties = properties;
        this.auditLog = auditLog;
        this.problemWriter = problemWriter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maxTrackedClients())
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean transfer = HttpMethod.POST.matches(request.getMethod()) && request.getRequestURI().equals("/api/transfers");
        RateLimitProperties.Policy policy = transfer ? properties.transfers() : properties.api();
        String principal = principal();
        String key = (transfer ? "transfers:" : "api:") + (principal != null ? principal : "ip:" + request.getRemoteAddr());

        Bucket bucket = buckets.get(key, _ -> newBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        auditLog.record(SecurityEventType.RATE_LIMITED, principal, request.getRemoteAddr(), request.getRequestURI(),
                Map.of("rate_limit.policy", transfer ? "transfers" : "api"));
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        problemWriter.write(request, response, Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate-limited",
                "Too many requests", "Request budget exhausted. Retry after " + retryAfterSeconds + "s."));
    }

    private static Bucket newBucket(RateLimitProperties.Policy policy) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(policy.capacity()).refillGreedy(policy.refillTokens(), policy.refillPeriod()))
                .build();
    }

    private static String principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }
}
