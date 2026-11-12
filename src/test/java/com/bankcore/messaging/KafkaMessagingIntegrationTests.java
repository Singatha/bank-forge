package com.bankcore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import com.bankcore.config.MessagingProperties;
import com.bankcore.messaging.application.OutboxPublisher;
import com.bankcore.messaging.domain.OutboxEvent;
import com.bankcore.messaging.infrastructure.OutboxEventRepository;

@SpringBootTest(properties = {
        "bankcore.features.development-funding-enabled=true",
        "bankcore.messaging.enabled=true",
        "bankcore.messaging.publisher-initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class KafkaMessagingIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.2");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MessagingProperties messagingProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishesACommittedOutboxEventAndCreatesNotifications() throws Exception {
        TransferFixture fixture = completedTransfer("publish");
        OutboxEvent event = eventFor(fixture.transferId());

        assertThat(event.getStatus().name()).isEqualTo("PENDING");
        assertThat(outboxPublisher.publishBatch()).isPositive();

        awaitCount("select count(*) from notifications where transfer_id = ?", 2, fixture.transferId());
        awaitCount("select count(*) from processed_events where event_id = ?", 1, event.getId());

        assertThat(jdbcTemplate.queryForObject(
                "select status from outbox_events where id = ?", String.class, event.getId()))
                .isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForList(
                "select type from notifications where transfer_id = ? order by type", String.class,
                fixture.transferId()))
                .containsExactly("TRANSFER_RECEIVED", "TRANSFER_SENT");
    }

    @Test
    void publishesAFailedTransferEventAndCreatesASourceNotification() throws Exception {
        TransferFixture fixture = failedTransfer("failed");
        OutboxEvent event = eventFor(fixture.transferId());

        assertThat(outboxPublisher.publishBatch()).isPositive();

        awaitCount("select count(*) from notifications where event_id = ?", 1, event.getId());
        awaitCount("select count(*) from processed_events where event_id = ?", 1, event.getId());

        assertThat(jdbcTemplate.queryForObject(
                "select type from notifications where event_id = ?", String.class, event.getId()))
                .isEqualTo("TRANSFER_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from outbox_events where id = ?", String.class, event.getId()))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void ignoresDuplicateEventsForTheSameConsumer() throws Exception {
        TransferFixture fixture = completedTransfer("duplicate");
        OutboxEvent event = eventFor(fixture.transferId());
        String body = objectMapper.writeValueAsString(event.envelope());

        kafkaTemplate.send(messagingProperties.transferTopic(), fixture.transferId().toString(), body)
                .get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(messagingProperties.transferTopic(), fixture.transferId().toString(), body)
                .get(10, TimeUnit.SECONDS);

        awaitCount("select count(*) from processed_events where event_id = ?", 1, event.getId());
        awaitCount("select count(*) from notifications where event_id = ?", 2, event.getId());
        Thread.sleep(500);

        assertThat(count("select count(*) from processed_events where event_id = ?", event.getId())).isOne();
        assertThat(count("select count(*) from notifications where event_id = ?", event.getId())).isEqualTo(2);
    }

    @Test
    void routesAnUnprocessableEventToTheDeadLetterTopicAfterRetries() throws Exception {
        String malformedBody = "{not-a-transfer-event}";
        String eventKey = UUID.randomUUID().toString();

        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(messagingProperties.deadLetterTopic()));
            kafkaTemplate.send(messagingProperties.transferTopic(), eventKey, malformedBody)
                    .get(10, TimeUnit.SECONDS);

            ConsumerRecord<String, String> deadLetter = pollForRecord(consumer, eventKey);

            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.topic()).isEqualTo(messagingProperties.deadLetterTopic());
            assertThat(deadLetter.value()).isEqualTo(malformedBody);
        }
    }

    private TransferFixture completedTransfer(String testName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        CustomerAccount source = customerWithAccount(testName + "-source-" + suffix + "@example.com");
        CustomerAccount destination = customerWithAccount(testName + "-destination-" + suffix + "@example.com");
        fund(source, "100.00");

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(source.token()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + source.accountId() + "\","
                                + "\"destinationAccountId\":\"" + destination.accountId() + "\","
                                + "\"amount\":25.00,\"currency\":\"ZAR\","
                                + "\"description\":\"Kafka integration test\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID transferId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
        return new TransferFixture(transferId);
    }

    private TransferFixture failedTransfer(String testName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        CustomerAccount source = customerWithAccount(testName + "-source-" + suffix + "@example.com");
        CustomerAccount destination = customerWithAccount(testName + "-destination-" + suffix + "@example.com");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearer(source.token()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + source.accountId() + "\","
                                + "\"destinationAccountId\":\"" + destination.accountId() + "\","
                                + "\"amount\":25.00,\"currency\":\"ZAR\","
                                + "\"description\":\"Kafka failed transfer test\"}"))
                .andExpect(status().isUnprocessableContent());
        UUID transferId = jdbcTemplate.queryForObject(
                "select id from transfers where source_account_id = ?", UUID.class, source.accountId());
        return new TransferFixture(transferId);
    }

    private OutboxEvent eventFor(UUID transferId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(transferId))
                .findFirst()
                .orElseThrow();
    }

    private CustomerAccount customerWithAccount(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Kafka\",\"lastName\":\"Tester\","
                                + "\"phoneNumber\":\"+27820000001\"}"))
                .andExpect(status().isCreated());
        MvcResult accountResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"CHECKING\",\"currency\":\"ZAR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID accountId = UUID.fromString(JsonPath.read(
                accountResult.getResponse().getContentAsString(), "$.id"));
        return new CustomerAccount(token, accountId);
    }

    private void fund(CustomerAccount account, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/development/accounts/{id}/fund", account.accountId())
                        .header("Authorization", bearer(account.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"currency\":\"ZAR\"}"))
                .andExpect(status().isOk());
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private ConsumerRecord<String, String> pollForRecord(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private void awaitCount(String sql, int expected, Object... arguments) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (count(sql, arguments) == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(count(sql, arguments)).isEqualTo(expected);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record CustomerAccount(String token, UUID accountId) {
    }

    private record TransferFixture(UUID transferId) {
    }
}
