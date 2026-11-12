package com.bankcore.messaging.application;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bankcore.config.MessagingProperties;
import com.bankcore.messaging.domain.OutboxEvent;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "bankcore.messaging", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxClaimService claimService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;

    public OutboxPublisher(OutboxClaimService claimService,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper,
                           MessagingProperties properties) {
        this.claimService = claimService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${bankcore.messaging.publisher-fixed-delay}",
            initialDelayString = "${bankcore.messaging.publisher-initial-delay}")
    public void scheduledPublish() {
        publishBatch();
    }

    public int publishBatch() {
        var events = claimService.claimBatch();
        for (OutboxEvent event : events) {
            publish(event);
        }
        return events.size();
    }

    private void publish(OutboxEvent event) {
        try {
            String body = objectMapper.writeValueAsString(event.envelope());
            kafkaTemplate.send(
                            properties.transferTopic(),
                            event.getAggregateId().toString(),
                            body)
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            claimService.markPublished(event.getId());
            LOGGER.info("outbox_event_published eventId={} aggregateId={}",
                    event.getId(), event.getAggregateId());
        } catch (Exception exception) {
            claimService.markFailed(event.getId(), exception);
            LOGGER.warn("outbox_event_publish_failed eventId={} errorType={}",
                    event.getId(), exception.getClass().getSimpleName());
        }
    }
}
