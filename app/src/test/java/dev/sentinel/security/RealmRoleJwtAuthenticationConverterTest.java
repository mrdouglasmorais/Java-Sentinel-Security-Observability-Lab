package dev.sentinel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RealmRoleJwtAuthenticationConverterTest {

    private final RealmRoleJwtAuthenticationConverter converter = new RealmRoleJwtAuthenticationConverter();

    @Test
    void mapsKnownRealmRolesAndIgnoresUnknownOnes() {
        var token = converter.convert(jwt(Map.of("roles", List.of("customer", "lab-operator", "realm-admin", "offline_access"))));

        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_LAB_OPERATOR");
        assertThat(token.getName()).isEqualTo("alice");
    }

    @Test
    void tokenWithoutRealmRolesGetsNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of())).getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> realmAccess) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("8a1c0f8e-subject")
                .claim("preferred_username", "alice")
                .claim("realm_access", realmAccess)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
