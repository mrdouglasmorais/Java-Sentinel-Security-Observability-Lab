package dev.sentinel.payments.application;

import java.util.Map;
import java.util.function.Supplier;

import dev.sentinel.identity.CurrentUser;
import dev.sentinel.security.SecurityAuditLog;

import org.springframework.stereotype.Component;

/**
 * Hides a resource the caller must not see and records the attempt. The response is the same as "not found",
 * so probing ids does not confirm that the resource exists.
 */
@Component
class ResourceGuard {

    private final SecurityAuditLog auditLog;

    ResourceGuard(SecurityAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    <T> T require(CurrentUser user, T resource, boolean allowed, String resourceType, String resourceId,
            Supplier<? extends RuntimeException> denied) {
        return require(user, resource, allowed, resourceType, resourceId, Map.of(), denied);
    }

    <T> T require(CurrentUser user, T resource, boolean allowed, String resourceType, String resourceId,
            Map<String, String> extra, Supplier<? extends RuntimeException> denied) {
        if (allowed) {
            return resource;
        }
        auditLog.ownershipViolation(user.username(), resourceType, resourceId, extra);
        throw denied.get();
    }
}
