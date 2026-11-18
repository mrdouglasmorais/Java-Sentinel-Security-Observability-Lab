package dev.sentinel.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sentinel.identity.Role;
import dev.sentinel.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "sentinel.security.rate-limit.api.capacity=3",
        "sentinel.security.rate-limit.api.refill-tokens=1",
        "sentinel.security.rate-limit.api.refill-period=1m"
})
class RateLimitIT extends IntegrationTest {

    @Test
    void clientExceedingItsBudgetGets429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/accounts").with(user("greedy", Role.CUSTOMER))).andExpect(status().isOk());
        }

        mvc.perform(get("/api/accounts").with(user("greedy", Role.CUSTOMER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:rate-limited"));
    }

    @Test
    void budgetsAreIsolatedPerClient() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/accounts").with(user("noisy", Role.CUSTOMER))).andExpect(status().isOk());
        }

        mvc.perform(get("/api/accounts").with(user("polite", Role.CUSTOMER))).andExpect(status().isOk());
    }
}
