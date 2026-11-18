package dev.sentinel.identity;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public CurrentUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated JWT principal");
        }
        String username = jwt.getClaimAsString("preferred_username");
        return new CurrentUser(jwt.getSubject(), username != null ? username : jwt.getSubject(), rolesOf(authentication));
    }

    private static Set<Role> rolesOf(Authentication authentication) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            for (Role role : Role.values()) {
                if (role.authority().equals(authority.getAuthority())) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}
