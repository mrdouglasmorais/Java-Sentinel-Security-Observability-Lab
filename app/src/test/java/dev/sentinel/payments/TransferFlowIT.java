package dev.sentinel.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import dev.sentinel.identity.Role;
import dev.sentinel.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TransferFlowIT extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void completedTransferMovesFundsAndWritesTheLedger() throws Exception {
        String response = transfer("alice", ALICE_CHECKING, BOB_CHECKING, "25.50", key())
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decision").value("risk_approved"))
                .andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.read(response, "$.id");

        mvc.perform(get("/api/accounts/{id}/ledger", BOB_CHECKING).with(user("bob", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transferId").value(transferId))
                .andExpect(jsonPath("$[0].direction").value("CREDIT"));
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheOriginalTransfer() throws Exception {
        String key = key();
        String first = transfer("alice", ALICE_CHECKING, ALICE_SAVINGS, "10.00", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        transfer("alice", ALICE_CHECKING, ALICE_SAVINGS, "10.00", key)
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(first));
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentPayloadIsAConflict() throws Exception {
        String key = key();
        transfer("alice", ALICE_CHECKING, ALICE_SAVINGS, "10.00", key).andExpect(status().isCreated());

        transfer("alice", ALICE_CHECKING, ALICE_SAVINGS, "99.00", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:idempotency-conflict"));
    }

    @Test
    void concurrentTransfersInOppositeDirectionsDoNotDeadlock() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> statuses = IntStream.range(0, 60)
                    .mapToObj(i -> executor.submit(() -> i % 2 == 0
                            ? transfer("alice", ALICE_CHECKING, BOB_CHECKING, "1.00", key()).andReturn().getResponse().getStatus()
                            : transfer("bob", BOB_CHECKING, ALICE_CHECKING, "1.00", key()).andReturn().getResponse().getStatus()))
                    .toList();
            for (Future<Integer> status : statuses) {
                assertThat(status.get()).isEqualTo(201);
            }
        }
    }

    @Test
    void lockTimeoutIsReportedAsTemporarilyUnavailable() throws Exception {
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT 1 FROM account WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(ALICE_SAVINGS));
                lock.executeQuery();
            }

            transfer("alice", ALICE_CHECKING, ALICE_SAVINGS, "5.00", key())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:temporarily-unavailable"));

            blocker.rollback();
        }
    }

    @Test
    void highRiskTransferIsRejectedWithoutMovingFunds() throws Exception {
        transfer("alice", ALICE_CHECKING, BOB_CHECKING, "60000.00", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:transfer-rejected"))
                .andExpect(jsonPath("$.transfer_id").exists());
    }

    @Test
    void cannotTransferFromAnAccountOwnedBySomeoneElse() throws Exception {
        transfer("bob", ALICE_CHECKING, BOB_CHECKING, "1.00", key())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:sentinel:account-not-found"));
    }

    @Test
    void invalidPayloadIsRejectedWithProblemDetails() throws Exception {
        transfer("alice", ALICE_CHECKING, BOB_CHECKING, "-5", key())
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void whenTheRiskEngineIsDownLowValueTransfersCompleteAndHighValueOnesWaitForReview() throws Exception {
        mvc.perform(put("/api/lab/faults/RISK_ENGINE").with(user("ops", Role.LAB_OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseLatency":"PT0S","jitter":"PT0S","spikeProbability":0,"spikeLatency":"PT0S","errorRate":1.0}
                                """))
                .andExpect(status().isOk());

        transfer("alice", ALICE_CHECKING, BOB_CHECKING, "50.00", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("fallback_low_value:error"));

        transfer("alice", ALICE_CHECKING, BOB_CHECKING, "5000.00", key())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @Test
    void slowRiskEngineIsCutByTheTimeLimiter() throws Exception {
        mvc.perform(put("/api/lab/faults/RISK_ENGINE").with(user("ops", Role.LAB_OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseLatency":"PT2S","jitter":"PT0S","spikeProbability":0,"spikeLatency":"PT0S","errorRate":0}
                                """))
                .andExpect(status().isOk());

        transfer("alice", ALICE_CHECKING, BOB_CHECKING, "5000.00", key())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("fallback_manual_review:timeout"));
    }

    private ResultActions transfer(String username, String source, String target, String amount, String key) throws Exception {
        return mvc.perform(post("/api/transfers")
                .with(user(username, Role.CUSTOMER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"%s","targetAccountId":"%s","amount":%s}
                        """.formatted(source, target, amount)));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
