package dev.sentinel.identity;

import java.util.Arrays;
import java.util.Optional;

/**
 * Application roles. The identity provider issues them as realm roles (e.g. {@code lab-operator});
 * Spring Security sees them as authorities (e.g. {@code ROLE_LAB_OPERATOR}).
 */
public enum Role {
    CUSTOMER("customer"),
    AUDITOR("auditor"),
    LAB_OPERATOR("lab-operator");

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public static Optional<Role> fromClaim(String value) {
        return Arrays.stream(values()).filter(role -> role.claimValue.equals(value)).findFirst();
    }
}
