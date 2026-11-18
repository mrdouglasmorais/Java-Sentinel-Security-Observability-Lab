package dev.sentinel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sentinel.identity.Role;
import dev.sentinel.support.IntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ApiSecurityIT extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Test
    void anonymousRequestsGet401WithBearerChallengeAndProblemDetails() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:unauthenticated"));
    }

    @Test
    void customersOnlySeeTheirOwnAccounts() throws Exception {
        mvc.perform(get("/api/accounts").with(user("alice", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void probingSomeoneElsesAccountLooksLikeNotFoundAndIsRecorded() throws Exception {
        double before = securityEvents("ownership_violation");

        mvc.perform(get("/api/accounts/{id}", ALICE_CHECKING).with(user("bob", Role.CUSTOMER)))
                .andExpect(status().isNotFound());

        assertThat(securityEvents("ownership_violation")).isEqualTo(before + 1);
    }

    @Test
    void auditorsCanReadAnyAccountButCannotTransfer() throws Exception {
        mvc.perform(get("/api/accounts/{id}", ALICE_CHECKING).with(user("carol", Role.AUDITOR)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/transfers").with(user("carol", Role.AUDITOR))
                        .header("Idempotency-Key", "auditor-attempt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void labControlPlaneRequiresTheLabOperatorRole() throws Exception {
        double before = securityEvents("access_denied");

        mvc.perform(post("/api/lab/scenarios/dependency-outage").with(user("alice", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:forbidden"));

        assertThat(securityEvents("access_denied")).isEqualTo(before + 1);

        mvc.perform(post("/api/lab/scenarios/dependency-outage").with(user("ops", Role.LAB_OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RISK_ENGINE.errorRate").value(0.9));
    }

    @Test
    void unknownScenarioIsANotFoundProblem() throws Exception {
        mvc.perform(post("/api/lab/scenarios/meteor-strike").with(user("ops", Role.LAB_OPERATOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:unknown-scenario"));
    }

    private double securityEvents(String type) {
        return registry.find("sentinel.security.events").tag("type", type).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
