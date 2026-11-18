package dev.sentinel.identity;

import java.util.Set;

/**
 * The authenticated caller, decoupled from Spring Security and JWT types so domain code stays framework-free.
 *
 * @param subject  stable identifier from the identity provider ({@code sub})
 * @param username business identifier used for resource ownership ({@code preferred_username})
 */
public record CurrentUser(String subject, String username, Set<Role> roles) {

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
