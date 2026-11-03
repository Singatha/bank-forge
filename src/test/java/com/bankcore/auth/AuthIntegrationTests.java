package com.bankcore.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registrationNormalizesEmailHashesPasswordAndAssignsCustomerRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("  Alice@Example.COM  ")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        String passwordHash = jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?", String.class, "alice@example.com");
        assertThat(passwordHash).startsWith("$2").doesNotContain(PASSWORD);

        Integer customerRoles = jdbcTemplate.queryForObject("""
                select count(*)
                from user_roles ur
                join users u on u.id = ur.user_id
                join roles r on r.id = ur.role_id
                where u.email = ? and r.name = 'CUSTOMER'
                """, Integer.class, "alice@example.com");
        assertThat(customerRoles).isEqualTo(1);
    }

    @Test
    void duplicateRegistrationReturnsConflict() throws Exception {
        register("duplicate@example.com");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("DUPLICATE@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void loginIssuesTokensAndAccessTokenAuthenticatesProtectedEndpoint() throws Exception {
        register("login@example.com");

        Tokens tokens = login("login@example.com", PASSWORD);
        assertThat(tokens.accessToken()).hasSizeGreaterThan(100);
        assertThat(tokens.refreshToken()).hasSizeGreaterThan(32);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        String storedHash = jdbcTemplate.queryForObject(
                "select token_hash from refresh_tokens rt join users u on u.id = rt.user_id where u.email = ?",
                String.class, "login@example.com");
        assertThat(storedHash).hasSize(64).isNotEqualTo(tokens.refreshToken());
    }

    @Test
    void badCredentialsReturnTheSameUnauthorizedError() throws Exception {
        register("credentials@example.com");

        assertInvalidCredentials("credentials@example.com", "this password is wrong");
        assertInvalidCredentials("missing@example.com", "this password is wrong");
    }

    @Test
    void customerCannotAccessAdminEndpoint() throws Exception {
        register("customer@example.com");
        Tokens tokens = login("customer@example.com", PASSWORD);

        mockMvc.perform(get("/api/v1/auth/admin-check")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheWholeFamily() throws Exception {
        register("rotation@example.com");
        Tokens original = login("rotation@example.com", PASSWORD);
        Tokens replacement = refresh(original.refreshToken(), 200, null);

        assertThat(replacement.refreshToken()).isNotEqualTo(original.refreshToken());

        refresh(original.refreshToken(), 401, "REFRESH_TOKEN_REUSED");
        refresh(replacement.refreshToken(), 401, "REFRESH_TOKEN_REUSED");

        Integer activeTokens = jdbcTemplate.queryForObject("""
                select count(*) from refresh_tokens rt
                join users u on u.id = rt.user_id
                where u.email = ? and rt.revoked_at is null
                """, Integer.class, "rotation@example.com");
        assertThat(activeTokens).isZero();
    }

    @Test
    void logoutRevokesSessionAndIsIdempotentForUnknownTokens() throws Exception {
        register("logout@example.com");
        Tokens tokens = login("logout@example.com", PASSWORD);

        logout(tokens.refreshToken());
        refresh(tokens.refreshToken(), 401, "REFRESH_TOKEN_REUSED");
        logout("an-unknown-but-well-formed-enough-token");
    }

    @Test
    void disabledUserCannotUseAnAlreadyIssuedAccessToken() throws Exception {
        register("disabled@example.com");
        Tokens tokens = login("disabled@example.com", PASSWORD);
        jdbcTemplate.update("update users set status = 'DISABLED' where email = ?", "disabled@example.com");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void unauthenticatedRequestsUseTheStandardErrorContract() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/me"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated());
    }

    private Tokens login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn();
        return tokens(result);
    }

    private Tokens refresh(String refreshToken, int expectedStatus, String expectedCode) throws Exception {
        var action = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().is(expectedStatus));
        if (expectedCode != null) {
            action.andExpect(jsonPath("$.code").value(expectedCode));
            return null;
        }
        return tokens(action.andReturn());
    }

    private void logout(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken)))
                .andExpect(status().isNoContent());
    }

    private void assertInvalidCredentials(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("The email address or password is incorrect."));
    }

    private Tokens tokens(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private String registerJson(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String refreshJson(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private record Tokens(String accessToken, String refreshToken) {
    }
}
