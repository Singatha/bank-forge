package com.bankcore.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.account.domain.Account;
import com.bankcore.account.infrastructure.AccountRepository;
import com.bankcore.config.MessagingProperties;
import com.bankcore.messaging.domain.EventEnvelope;
import com.bankcore.messaging.domain.EventType;
import com.bankcore.notification.domain.Notification;
import com.bankcore.notification.domain.NotificationType;
import com.bankcore.notification.infrastructure.NotificationRepository;
import com.bankcore.notification.infrastructure.ProcessedEventRepository;
import com.bankcore.observability.application.BankCoreMetrics;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "bankcore.messaging", name = "enabled", havingValue = "true")
public class NotificationEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final AccountRepository accountRepository;
    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final MessagingProperties properties;
    private final Clock clock;
    private final BankCoreMetrics metrics;

    public NotificationEventConsumer(ObjectMapper objectMapper,
                                     AccountRepository accountRepository,
                                     NotificationRepository notificationRepository,
                                     ProcessedEventRepository processedEventRepository,
                                     MessagingProperties properties,
                                     Clock clock,
                                     BankCoreMetrics metrics) {
        this.objectMapper = objectMapper;
        this.accountRepository = accountRepository;
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${bankcore.messaging.transfer-topic}",
            groupId = "${bankcore.messaging.notification-consumer-group}")
    @Transactional
    public void consume(String body) {
        try {
            EventEnvelope event = parse(body);
            try (MDC.MDCCloseable ignored = MDC.putCloseable(
                    "correlationId", event.correlationId().toString())) {
                process(event);
            }
        } catch (RuntimeException exception) {
            metrics.kafkaConsumer("failed");
            throw exception;
        }
    }

    private void process(EventEnvelope event) {
        if (!"TRANSFER".equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unsupported aggregate type");
        }
        String consumerName = properties.notificationConsumerGroup();
        Instant now = Instant.now(clock);
        int inserted = processedEventRepository.insertIfAbsent(
                UUID.randomUUID(), consumerName, event.eventId(), now);
        if (inserted == 0) {
            metrics.kafkaConsumer("duplicate");
            LOGGER.info("notification_event_duplicate eventId={}", event.eventId());
            return;
        }

        List<Notification> notifications = createNotifications(event, now);
        notificationRepository.saveAll(notifications);
        metrics.kafkaConsumer("processed");
        LOGGER.info("notification_event_processed eventId={} notificationCount={}",
                event.eventId(), notifications.size());
    }

    private List<Notification> createNotifications(EventEnvelope event, Instant now) {
        Map<String, String> payload = event.payload();
        String reference = required(payload, "reference");
        Account source = account(requiredUuid(payload, "sourceAccountId"));

        if (event.eventType() == EventType.TRANSFER_FAILED) {
            return List.of(notification(event, source.getCustomerId(), NotificationType.TRANSFER_FAILED,
                    "Transfer failed", "Transfer " + reference + " could not be completed.", now));
        }

        Account destination = account(requiredUuid(payload, "destinationAccountId"));
        List<Notification> notifications = new ArrayList<>();
        notifications.add(notification(event, source.getCustomerId(), NotificationType.TRANSFER_SENT,
                "Transfer sent", "Transfer " + reference + " was completed.", now));
        notifications.add(notification(event, destination.getCustomerId(), NotificationType.TRANSFER_RECEIVED,
                "Transfer received", "Transfer " + reference + " was received.", now));
        return notifications;
    }

    private Notification notification(EventEnvelope event, UUID customerId, NotificationType type,
                                      String title, String message, Instant now) {
        return new Notification(UUID.randomUUID(), event.eventId(), event.aggregateId(),
                customerId, type, title, message, now);
    }

    private EventEnvelope parse(String body) {
        try {
            return objectMapper.readValue(body, EventEnvelope.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid transfer event", exception);
        }
    }

    private Account account(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Event account does not exist"));
    }

    private String required(Map<String, String> payload, String key) {
        String value = payload.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Event payload is missing " + key);
        }
        return value;
    }

    private UUID requiredUuid(Map<String, String> payload, String key) {
        return UUID.fromString(required(payload, key));
    }
}
