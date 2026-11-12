package com.bankcore.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.bankcore.observability.application.BankCoreMetrics;
import com.bankcore.ratelimit.api.RateLimitInterceptor;

@SpringBootTest(properties = {
        "bankcore.rate-limiting.enabled=true",
        "bankcore.rate-limiting.authentication.limit=2",
        "bankcore.rate-limiting.authentication.window=1m",
        "bankcore.rate-limiting.transfers.limit=1",
        "bankcore.rate-limiting.transfers.window=1m"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RateLimitObservabilityIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BankCoreMetrics metrics;

    @Test
    void limitsAuthenticationAttemptsByRemoteAddress() throws Exception {
        String remoteAddress = "10.20.0.1";
        String body = "{\"email\":\"missing@example.com\",\"password\":\"" + PASSWORD + "\"}";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(remoteAddress(remoteAddress))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(RateLimitInterceptor.LIMIT_HEADER, "2"));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteAddress(remoteAddress))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitInterceptor.REMAINING_HEADER, "0"))
                .andExpect(header().exists(RateLimitInterceptor.RESET_HEADER))
                .andExpect(header().exists(RateLimitInterceptor.RETRY_AFTER_HEADER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void limitsTransferCreationByAuthenticatedUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "rate-transfer-" + suffix + "@example.com";
        String authAddress = "10.20.0.2";
        String token = registerAndLogin(email, authAddress);
        UUID accountId = createCustomerAndAccount(token);
        String transferBody = "{\"sourceAccountId\":\"" + accountId + "\","
                + "\"destinationAccountId\":\"" + accountId + "\","
                + "\"amount\":10.00,\"currency\":\"ZAR\",\"description\":\"Rate test\"}";

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(RateLimitInterceptor.REMAINING_HEADER, "0"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        metrics.rateLimitDecision("integration-test", "allowed");

        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("bankcore_rate_limit_decisions_total")
                .contains("application=\"bankcore\"");
    }

    @Test
    void readinessIncludesAvailableDatabaseAndRedisDependencies() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String registerAndLogin(String email, String remoteAddress) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteAddress(remoteAddress))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteAddress(remoteAddress))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private UUID createCustomerAndAccount(String token) throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Rate\",\"lastName\":\"Tester\","
                                + "\"phoneNumber\":\"+27820000001\"}"))
                .andExpect(status().isCreated());
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"CHECKING\",\"currency\":\"ZAR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
