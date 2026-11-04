package com.bankcore.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
class TransferIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void successfulTransferAtomicallyDebitsAndCreditsAccounts() throws Exception {
        CustomerAccount source = customerWithAccount("transfer-source@example.com");
        CustomerAccount destination = customerWithAccount("transfer-destination@example.com");
        fund(source, "1000.00");

        MvcResult result = transfer(source.token(), source.accountId(), destination.accountId(), "250.25")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("TR")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(250.25))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andReturn();

        assertBalance(source, "749.75");
        assertBalance(destination, "250.25");

        String transferId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId)
                        .header("Authorization", bearer(destination.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Test transfer"));
    }

    @Test
    void insufficientFundsRecordsFailureWithoutChangingBalances() throws Exception {
        CustomerAccount source = customerWithAccount("insufficient-source@example.com");
        CustomerAccount destination = customerWithAccount("insufficient-destination@example.com");
        fund(source, "100.00");

        transfer(source.token(), source.accountId(), destination.accountId(), "150.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertBalance(source, "100.00");
        assertBalance(destination, "0.00");

        var failure = jdbcTemplate.queryForMap("""
                select status, failure_reason from transfers
                where source_account_id = ?::uuid
                """, source.accountId());
        assertThat(failure.get("status")).isEqualTo("FAILED");
        assertThat(failure.get("failure_reason")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void rejectsInvalidTransferRequests() throws Exception {
        CustomerAccount customer = customerWithAccount("invalid-transfer@example.com");

        transfer(customer.token(), customer.accountId(), customer.accountId(), "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(customer.accountId(), customer.accountId(), "0.00", "ZAR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(customer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(customer.accountId(), customer.accountId(), "10.00", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void rejectsFrozenSourceAccountWithoutChangingBalances() throws Exception {
        CustomerAccount source = customerWithAccount("frozen-source@example.com");
        CustomerAccount destination = customerWithAccount("frozen-destination@example.com");
        fund(source, "100.00");
        jdbcTemplate.update("update accounts set status = 'FROZEN' where id = ?::uuid", source.accountId());

        transfer(source.token(), source.accountId(), destination.accountId(), "25.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertBalance(source, "100.00");
        assertBalance(destination, "0.00");
    }

    @Test
    void customerCannotTransferFromAnotherCustomersAccount() throws Exception {
        CustomerAccount owner = customerWithAccount("ownership-owner@example.com");
        CustomerAccount attacker = customerWithAccount("ownership-attacker@example.com");
        fund(owner, "100.00");

        transfer(attacker.token(), owner.accountId(), attacker.accountId(), "50.00")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertBalance(owner, "100.00");
        assertBalance(attacker, "0.00");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transfers where source_account_id = ?::uuid",
                Integer.class, owner.accountId())).isZero();
    }

    @Test
    void transferHistoryIsVisibleToBothAccountOwnersButNotStrangers() throws Exception {
        CustomerAccount source = customerWithAccount("history-source@example.com");
        CustomerAccount destination = customerWithAccount("history-destination@example.com");
        CustomerAccount stranger = customerWithAccount("history-stranger@example.com");
        fund(source, "100.00");

        MvcResult result = transfer(source.token(), source.accountId(), destination.accountId(), "20.00")
                .andExpect(status().isCreated())
                .andReturn();
        String transferId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/transfers").header("Authorization", bearer(source.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/transfers").header("Authorization", bearer(destination.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/transfers").header("Authorization", bearer(stranger.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId)
                        .header("Authorization", bearer(stranger.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void databaseFailureRollsBackDebitCreditAndTransferRecord() throws Exception {
        CustomerAccount source = customerWithAccount("rollback-source@example.com");
        CustomerAccount destination = customerWithAccount("rollback-destination@example.com");
        fund(source, "10.00");
        fund(destination, "99999999999999999.99");

        transfer(source.token(), source.accountId(), destination.accountId(), "1.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertBalance(source, "10.00");
        assertBalance(destination, "99999999999999999.99");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transfers where source_account_id = ?::uuid",
                Integer.class, source.accountId())).isZero();
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
        MvcResult accountResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"CHECKING\",\"currency\":\"ZAR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accountId = JsonPath.read(accountResult.getResponse().getContentAsString(), "$.id");
        return new CustomerAccount(token, accountId);
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

    private org.springframework.test.web.servlet.ResultActions transfer(
            String token, String sourceId, String destinationId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/transfers")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferJson(sourceId, destinationId, amount, "ZAR")));
    }

    private String transferJson(String sourceId, String destinationId, String amount, String currency) {
        return "{\"sourceAccountId\":\"" + sourceId + "\","
                + "\"destinationAccountId\":\"" + destinationId + "\","
                + "\"amount\":" + amount + ",\"currency\":\"" + currency + "\","
                + "\"description\":\"Test transfer\"}";
    }

    private void assertBalance(CustomerAccount account, String expected) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/{id}/balance", account.accountId())
                        .header("Authorization", bearer(account.token())))
                .andExpect(status().isOk())
                .andReturn();
        Number actual = JsonPath.read(result.getResponse().getContentAsString(), "$.availableBalance");
        assertThat(new BigDecimal(actual.toString())).isEqualByComparingTo(expected);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record CustomerAccount(String token, String accountId) {
    }
}
