package com.bankcore.account;

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
class CustomerAccountIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void authenticatedCustomerCreatesAndRetrievesProfile() throws Exception {
        String token = registerAndLogin("profile@example.com");

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Thandi", "Ndlovu", "+27821234567")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstName").value("Thandi"))
                .andExpect(jsonPath("$.email").value("profile@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/customers/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Ndlovu"))
                .andExpect(jsonPath("$.phoneNumber").value("+27821234567"));
    }

    @Test
    void customerProfileCanOnlyBeCreatedOnce() throws Exception {
        String token = registerAndLogin("duplicate-profile@example.com");
        createProfile(token, "Anele", "Dlamini");

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("Other", "Name", "+27821234568")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_PROFILE_EXISTS"));
    }

    @Test
    void activeCustomerCreatesListsAndRetrievesAccounts() throws Exception {
        String token = registerAndLogin("accounts@example.com");
        createProfile(token, "Kagiso", "Molefe");

        String checkingId = createAccount(token, "CHECKING");
        String savingsId = createAccount(token, "SAVINGS");

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountNumber").value(org.hamcrest.Matchers.startsWith("BC")))
                .andExpect(jsonPath("$[0].currency").value("ZAR"))
                .andExpect(jsonPath("$[0].availableBalance").value(0.00))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/accounts/{id}", checkingId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountType").value("CHECKING"));

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", savingsId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("ZAR"))
                .andExpect(jsonPath("$.availableBalance").value(0.00));
    }

    @Test
    void accountCreationRequiresCustomerProfile() throws Exception {
        String token = registerAndLogin("missing-profile@example.com");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("CHECKING")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_PROFILE_REQUIRED"));
    }

    @Test
    void customersCannotDiscoverOrListAnotherCustomersAccounts() throws Exception {
        String ownerToken = registerAndLogin("owner@example.com");
        createProfile(ownerToken, "Owner", "Customer");
        String accountId = createAccount(ownerToken, "CHECKING");

        String strangerToken = registerAndLogin("stranger@example.com");
        createProfile(strangerToken, "Other", "Customer");
        createAccount(strangerToken, "SAVINGS");

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .header("Authorization", bearer(strangerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", bearer(strangerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountType").value("SAVINGS"));
    }

    @Test
    void supportCanReadAccountsButCannotApplyDevelopmentFunding() throws Exception {
        String ownerToken = registerAndLogin("staff-owner@example.com");
        createProfile(ownerToken, "Staff", "Owner");
        String accountId = createAccount(ownerToken, "CHECKING");

        register("support@example.com");
        jdbcTemplate.update("""
                insert into user_roles (user_id, role_id)
                select u.id, r.id from users u cross join roles r
                where u.email = ? and r.name = 'SUPPORT'
                """, "support@example.com");
        String supportToken = login("support@example.com");

        mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .header("Authorization", bearer(supportToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/development/accounts/{id}/fund", accountId)
                        .header("Authorization", bearer(supportToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingJson("100.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void developmentFundingCreditsOwnedActiveAccountUsingBigDecimal() throws Exception {
        String token = registerAndLogin("funding@example.com");
        createProfile(token, "Funded", "Customer");
        String accountId = createAccount(token, "CHECKING");

        fund(token, accountId, "1000.00");
        MvcResult result = fund(token, accountId, "250.25");

        Number balance = JsonPath.read(result.getResponse().getContentAsString(), "$.availableBalance");
        assertThat(new BigDecimal(balance.toString())).isEqualByComparingTo("1250.25");

        jdbcTemplate.update("update accounts set status = 'FROZEN' where id = ?::uuid", accountId);
        mockMvc.perform(post("/api/v1/development/accounts/{id}/fund", accountId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingJson("10.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    private String registerAndLogin(String email) throws Exception {
        register(email);
        return login(email);
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

    private void createProfile(String token, String firstName, String lastName) throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson(firstName, lastName, "+27820000001")))
                .andExpect(status().isCreated());
    }

    private String createAccount(String token, String accountType) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(accountType)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private MvcResult fund(String token, String accountId, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/development/accounts/{id}/fund", accountId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fundingJson(amount)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String profileJson(String firstName, String lastName, String phoneNumber) {
        return "{\"firstName\":\"" + firstName + "\",\"lastName\":\"" + lastName
                + "\",\"phoneNumber\":\"" + phoneNumber + "\"}";
    }

    private String accountJson(String accountType) {
        return "{\"accountType\":\"" + accountType + "\",\"currency\":\"ZAR\"}";
    }

    private String fundingJson(String amount) {
        return "{\"amount\":" + amount + ",\"currency\":\"ZAR\"}";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
