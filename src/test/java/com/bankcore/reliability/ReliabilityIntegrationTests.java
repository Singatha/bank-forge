package com.bankcore.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "bankcore.features.development-funding-enabled=true")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReliabilityIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentDuplicateRequestsExecuteTransferExactlyOnce() throws Exception {
        CustomerAccount source = customerWithAccount("idem-source@example.com");
        CustomerAccount destination = customerWithAccount("idem-destination@example.com");
        fund(source, "100.00");
        String idempotencyKey = UUID.randomUUID().toString();

        List<MvcResult> results = concurrently(
                () -> performTransfer(source.token(), idempotencyKey,
                        source.accountId(), destination.accountId(), "40.00", null),
                () -> performTransfer(source.token(), idempotencyKey,
                        source.accountId(), destination.accountId(), "40.00", null));

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsOnly(201);
        assertThat(results).extracting(result ->
                        result.getResponse().getHeader("Idempotency-Replayed"))
                .containsExactlyInAnyOrder("false", "true");
        String originalTransferId = JsonPath.read(
                results.getFirst().getResponse().getContentAsString(), "$.id");
        assertThat(results).extracting(result ->
                        JsonPath.<String>read(result.getResponse().getContentAsString(), "$.id"))
                .containsOnly(originalTransferId);

        assertBalance(source.accountId(), "60.00");
        assertBalance(destination.accountId(), "40.00");
        assertThat(count("select count(*) from transfers where source_account_id = ?::uuid",
                source.accountId())).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries where account_id = ?::uuid",
                source.accountId())).isEqualTo(1);
        assertThat(count("select count(*) from idempotency_records where idempotency_key = ?",
                idempotencyKey)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'TRANSFER_COMPLETED'"
                + " and resource_id in (select id from transfers where source_account_id = ?::uuid)",
                source.accountId())).isEqualTo(1);
    }

    @Test
    void concurrentTransfersCannotOverdrawSourceBalance() throws Exception {
        CustomerAccount source = customerWithAccount("concurrency-source@example.com");
        CustomerAccount firstDestination = customerWithAccount("concurrency-first@example.com");
        CustomerAccount secondDestination = customerWithAccount("concurrency-second@example.com");
        fund(source, "100.00");

        List<MvcResult> results = concurrently(
                () -> performTransfer(source.token(), UUID.randomUUID().toString(),
                        source.accountId(), firstDestination.accountId(), "70.00", null),
                () -> performTransfer(source.token(), UUID.randomUUID().toString(),
                        source.accountId(), secondDestination.accountId(), "70.00", null));

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 422);
        assertBalance(source.accountId(), "30.00");
        BigDecimal destinationTotal = balance(firstDestination.accountId())
                .add(balance(secondDestination.accountId()));
        assertThat(destinationTotal).isEqualByComparingTo("70.00");
        assertThat(count("select count(*) from transfers where source_account_id = ?::uuid and status = 'COMPLETED'",
                source.accountId())).isEqualTo(1);
        assertThat(count("select count(*) from transfers where source_account_id = ?::uuid and status = 'FAILED'",
                source.accountId())).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries entry join transfers transfer"
                + " on transfer.id = entry.transfer_id where transfer.source_account_id = ?::uuid",
                source.accountId())).isEqualTo(2);
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentRequest() throws Exception {
        CustomerAccount source = customerWithAccount("reuse-source@example.com");
        CustomerAccount destination = customerWithAccount("reuse-destination@example.com");
        fund(source, "100.00");
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(transferRequest(source.token(), idempotencyKey,
                        source.accountId(), destination.accountId(), "10.00", null))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"));

        mockMvc.perform(transferRequest(source.token(), idempotencyKey,
                        source.accountId(), destination.accountId(), "11.00", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(source.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source.accountId(), destination.accountId(), "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        String failedKey = UUID.randomUUID().toString();
        mockMvc.perform(transferRequest(source.token(), failedKey,
                        source.accountId(), destination.accountId(), "100.00", null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mockMvc.perform(transferRequest(source.token(), failedKey,
                        source.accountId(), destination.accountId(), "100.00", null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertBalance(source.accountId(), "90.00");
        assertBalance(destination.accountId(), "10.00");
        assertThat(count("select count(*) from transfers where source_account_id = ?::uuid and status = 'FAILED'",
                source.accountId())).isEqualTo(1);
    }

    @Test
    void collectionEndpointsReturnBoundedPageMetadata() throws Exception {
        CustomerAccount customer = customerWithAccount("pagination@example.com");
        createAccount(customer.token(), "SAVINGS");
        createAccount(customer.token(), "CHECKING");

        mockMvc.perform(get("/api/v1/accounts?page=0&size=2")
                        .header("Authorization", bearer(customer.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/v1/accounts?page=1&size=2")
                        .header("Authorization", bearer(customer.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/api/v1/accounts?size=101")
                        .header("Authorization", bearer(customer.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_REQUEST"));
    }

    @Test
    void correlationIdsFlowIntoAppendOnlyAuditRecords() throws Exception {
        CustomerAccount source = customerWithAccount("audit-source@example.com");
        CustomerAccount destination = customerWithAccount("audit-destination@example.com");
        fund(source, "50.00");
        UUID requestId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(transferRequest(source.token(), UUID.randomUUID().toString(),
                        source.accountId(), destination.accountId(), "5.00", correlationId)
                        .header("X-Request-ID", requestId.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", requestId.toString()))
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andReturn();
        String transferId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        var audit = jdbcTemplate.queryForMap("""
                select id, request_id, correlation_id, metadata
                from audit_logs
                where action = 'TRANSFER_COMPLETED' and resource_id = ?::uuid
                """, transferId);
        assertThat(audit.get("request_id").toString()).isEqualTo(requestId.toString());
        assertThat(audit.get("correlation_id").toString()).isEqualTo(correlationId.toString());
        assertThat(audit.get("metadata").toString()).doesNotContain(PASSWORD);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update audit_logs set action = 'TRANSFER_FAILED' where id = ?::uuid",
                audit.get("id")))
                .hasMessageContaining("audit logs are append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from audit_logs where id = ?::uuid", audit.get("id")))
                .hasMessageContaining("audit logs are append-only");
    }

    private List<MvcResult> concurrently(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> firstFuture = executor.submit(awaitStart(first, ready, start));
            Future<MvcResult> secondFuture = executor.submit(awaitStart(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<MvcResult> awaitStart(Callable<MvcResult> request, CountDownLatch ready,
                                            CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test start timed out");
            }
            return request.call();
        };
    }

    private MvcResult performTransfer(String token, String idempotencyKey, String sourceId,
                                      String destinationId, String amount, UUID correlationId) throws Exception {
        return mockMvc.perform(transferRequest(
                        token, idempotencyKey, sourceId, destinationId, amount, correlationId))
                .andReturn();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transferRequest(
            String token, String idempotencyKey, String sourceId,
            String destinationId, String amount, UUID correlationId) {
        var request = post("/api/v1/transfers")
                .header("Authorization", bearer(token))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferJson(sourceId, destinationId, amount));
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId.toString());
        }
        return request;
    }

    private CustomerAccount customerWithAccount(String email) throws Exception {
        register(email);
        String token = login(email);
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Test\",\"lastName\":\"Customer\","
                                + "\"phoneNumber\":\"+27820000001\"}"))
                .andExpect(status().isCreated());
        return new CustomerAccount(token, createAccount(token, "CHECKING"));
    }

    private String createAccount(String token, String accountType) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"" + accountType + "\",\"currency\":\"ZAR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void fund(CustomerAccount account, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/development/accounts/{id}/fund", account.accountId())
                        .header("Authorization", bearer(account.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"currency\":\"ZAR\"}"))
                .andExpect(status().isOk());
    }

    private String transferJson(String sourceId, String destinationId, String amount) {
        return "{\"sourceAccountId\":\"" + sourceId + "\","
                + "\"destinationAccountId\":\"" + destinationId + "\","
                + "\"amount\":" + amount + ",\"currency\":\"ZAR\","
                + "\"description\":\"Reliability test\"}";
    }

    private void assertBalance(String accountId, String expected) {
        assertThat(balance(accountId)).isEqualByComparingTo(expected);
    }

    private BigDecimal balance(String accountId) {
        return jdbcTemplate.queryForObject(
                "select available_balance from accounts where id = ?::uuid",
                BigDecimal.class, accountId);
    }

    private int count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Integer.class, argument);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record CustomerAccount(String token, String accountId) {
    }
}
