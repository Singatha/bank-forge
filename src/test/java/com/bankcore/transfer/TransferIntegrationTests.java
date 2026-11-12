package com.bankcore.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

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
        var ledgerEntries = jdbcTemplate.queryForList("""
                select account_id, entry_type, amount, currency
                from ledger_entries
                where transfer_id = ?::uuid
                order by entry_type
                """, transferId);
        assertThat(ledgerEntries).hasSize(2);
        assertThat(ledgerEntries).extracting(entry -> entry.get("entry_type"))
                .containsExactly("CREDIT", "DEBIT");
        assertThat(ledgerEntries).extracting(entry -> entry.get("account_id").toString())
                .containsExactlyInAnyOrder(source.accountId(), destination.accountId());
        assertThat(ledgerEntries).allSatisfy(entry -> {
            assertThat((BigDecimal) entry.get("amount")).isEqualByComparingTo("250.25");
            assertThat(entry.get("currency")).isEqualTo("ZAR");
        });
        var event = jdbcTemplate.queryForMap("""
                select event_type, aggregate_type, status, payload
                from outbox_events
                where aggregate_id = ?::uuid
                """, transferId);
        assertThat(event.get("event_type")).isEqualTo("TRANSFER_COMPLETED");
        assertThat(event.get("aggregate_type")).isEqualTo("TRANSFER");
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("payload").toString())
                .contains(source.accountId(), destination.accountId())
                .doesNotContain("password", "token");

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
        UUID failedTransferId = jdbcTemplate.queryForObject("""
                select id from transfers where source_account_id = ?::uuid
                """, UUID.class, source.accountId());
        assertThat(jdbcTemplate.queryForObject("""
                select event_type from outbox_events where aggregate_id = ?
                """, String.class, failedTransferId)).isEqualTo("TRANSFER_FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ledger_entries entry
                join transfers transfer on transfer.id = entry.transfer_id
                where transfer.source_account_id = ?::uuid
                """, Integer.class, source.accountId())).isZero();
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
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/transfers").header("Authorization", bearer(destination.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/transfers").header("Authorization", bearer(stranger.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId)
                        .header("Authorization", bearer(stranger.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void accountTransactionHistoryComesFromImmutableLedgerEntries() throws Exception {
        CustomerAccount source = customerWithAccount("ledger-history-source@example.com");
        CustomerAccount destination = customerWithAccount("ledger-history-destination@example.com");
        CustomerAccount stranger = customerWithAccount("ledger-history-stranger@example.com");
        fund(source, "100.00");

        MvcResult result = transfer(source.token(), source.accountId(), destination.accountId(), "35.75")
                .andExpect(status().isCreated())
                .andReturn();
        String transferId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/accounts/{id}/transactions", source.accountId())
                        .header("Authorization", bearer(source.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transferId").value(transferId))
                .andExpect(jsonPath("$.content[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].amount").value(35.75))
                .andExpect(jsonPath("$.content[0].transferReference").value(
                        org.hamcrest.Matchers.startsWith("TR")));

        mockMvc.perform(get("/api/v1/accounts/{id}/transactions", destination.accountId())
                        .header("Authorization", bearer(destination.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].entryType").value("CREDIT"));

        mockMvc.perform(get("/api/v1/accounts/{id}/transactions", source.accountId())
                        .header("Authorization", bearer(stranger.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update ledger_entries set amount = 1.00 where transfer_id = ?::uuid", transferId))
                .hasMessageContaining("ledger entries are append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from ledger_entries where transfer_id = ?::uuid", transferId))
                .hasMessageContaining("ledger entries are append-only");
        assertThat(jdbcTemplate.queryForObject(
                "select amount from ledger_entries where transfer_id = ?::uuid and entry_type = 'DEBIT'",
                BigDecimal.class, transferId)).isEqualByComparingTo("35.75");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ledger_entries where transfer_id = ?::uuid",
                Integer.class, transferId)).isEqualTo(2);
    }

    @Test
    void databaseRejectsCompletedTransferWithoutBalancedLedgerEntries() throws Exception {
        CustomerAccount source = customerWithAccount("missing-ledger-source@example.com");
        CustomerAccount destination = customerWithAccount("missing-ledger-destination@example.com");
        UUID userId = jdbcTemplate.queryForObject("""
                select customer.user_id
                from customers customer
                join accounts account on account.customer_id = customer.id
                where account.id = ?::uuid
                """, UUID.class, source.accountId());
        UUID transferId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into transfers (
                    id, reference, source_account_id, destination_account_id, initiated_by_user_id,
                    amount, currency, status, description, created_at, completed_at, failure_reason)
                values (?::uuid, ?, ?::uuid, ?::uuid, ?::uuid,
                        10.00, 'ZAR', 'COMPLETED', 'Invalid direct insert', now(), now(), null)
                """, transferId, "TR" + transferId.toString().replace("-", "").substring(0, 18).toUpperCase(),
                source.accountId(), destination.accountId(), userId))
                .hasMessageContaining("ledger is not balanced");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transfers where id = ?::uuid", Integer.class, transferId)).isZero();
    }

    @Test
    void databaseFailureRollsBackDebitCreditAndTransferRecord() throws Exception {
        CustomerAccount source = customerWithAccount("rollback-source@example.com");
        CustomerAccount destination = customerWithAccount("rollback-destination@example.com");
        fund(source, "10.00");
        fund(destination, "99999999999999999.99");
        int outboxCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events", Integer.class);

        transfer(source.token(), source.accountId(), destination.accountId(), "1.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertBalance(source, "10.00");
        assertBalance(destination, "99999999999999999.99");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transfers where source_account_id = ?::uuid",
                Integer.class, source.accountId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ledger_entries where account_id = ?::uuid",
                Integer.class, source.accountId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_events", Integer.class)).isEqualTo(outboxCountBefore);
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
                .header("Idempotency-Key", UUID.randomUUID().toString())
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
