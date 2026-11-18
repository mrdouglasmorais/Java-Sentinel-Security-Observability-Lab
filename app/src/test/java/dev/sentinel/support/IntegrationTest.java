package dev.sentinel.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Arrays;

import dev.sentinel.identity.Role;
import dev.sentinel.simulation.FaultInjector;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Full application against a real PostgreSQL 18 (Testcontainers), with the "lab" profile so seed data and the fault
 * injection endpoints are available. JWTs are injected with Spring Security Test; no identity provider is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("lab")
@Import(IntegrationTest.Containers.class)
public abstract class IntegrationTest {

    public static final String ALICE_CHECKING = "0193a000-0000-7000-8000-00000000a001";
    public static final String ALICE_SAVINGS = "0193a000-0000-7000-8000-00000000a002";
    public static final String BOB_CHECKING = "0193a000-0000-7000-8000-00000000b001";

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected FaultInjector faults;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakers;

    @AfterEach
    void resetLab() {
        faults.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(circuitBreaker -> circuitBreaker.reset());
    }

    protected static RequestPostProcessor user(String username, Role... roles) {
        return jwt()
                .jwt(token -> token.subject(username + "-subject").claim("preferred_username", username))
                .authorities(Arrays.stream(roles).<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority())).toList());
    }

    @Configuration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }
}
