package dev.sentinel.security;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Emits every security event twice, on purpose:
 * <ul>
 *   <li>a counter {@code sentinel_security_events_total{type,endpoint_group}} for dashboards and alerts. Tags are
 *   bounded enums only: never user ids, IPs or paths, which would explode cardinality;</li>
 *   <li>a structured audit log on the {@code sentinel.audit} logger with the high-cardinality details (principal,
 *   client address, path). The OpenTelemetry agent ships it to Loki with the active {@code trace_id}.</li>
 * </ul>
 * Tokens, credentials and request bodies are never logged.
 */
@Component
public class SecurityAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("sentinel.audit");

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public SecurityAuditLog(MeterRegistry registry) {
        this.registry = registry;
    }

    public void ownershipViolation(String principal, String resourceType, String resourceId, Map<String, String> extra) {
        Map<String, String> details = new HashMap<>(extra.size() + 2);
        details.put("resource.type", resourceType);
        details.put("resource.id", resourceId);
        details.putAll(extra);
        recordForCurrentRequest(SecurityEventType.OWNERSHIP_VIOLATION, principal, details);
    }

    public void recordForCurrentRequest(SecurityEventType type, String principal, Map<String, String> details) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            record(type, principal, request.getRemoteAddr(), request.getRequestURI(), details);
        } else {
            record(type, principal, null, null, details);
        }
    }

    public void record(SecurityEventType type, String principal, String clientAddress, String path, Map<String, String> details) {
        String group = EndpointGroup.of(path);
        counters.computeIfAbsent(type.tagValue() + '\u0000' + group, key -> Counter.builder("sentinel.security.events")
                .description("Security-relevant events (authentication failures, denials, rate limiting, lab changes)")
                .tag("type", type.tagValue())
                .tag("endpoint_group", group)
                .register(registry))
                .increment();

        LoggingEventBuilder event = AUDIT.atWarn()
                .addKeyValue("audit.event", type.tagValue())
                .addKeyValue("audit.principal", principal == null ? "anonymous" : principal)
                .addKeyValue("client.address", clientAddress == null ? "unknown" : clientAddress)
                .addKeyValue("url.path", path == null ? "unknown" : path);
        details.forEach(event::addKeyValue);
        event.log("security event {}", type.tagValue());
    }

    /**
     * Groups paths into a handful of buckets so the metric stays low-cardinality.
     */
    static final class EndpointGroup {

        private EndpointGroup() {
        }

        static String of(String path) {
            if (path == null) {
                return "other";
            }
            if (path.startsWith("/api/transfers")) {
                return "transfers";
            }
            if (path.startsWith("/api/accounts")) {
                return "accounts";
            }
            if (path.startsWith("/api/lab")) {
                return "lab";
            }
            return "other";
        }
    }
}
