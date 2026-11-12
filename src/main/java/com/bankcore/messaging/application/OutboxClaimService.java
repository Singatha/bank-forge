package com.bankcore.messaging.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.config.MessagingProperties;
import com.bankcore.messaging.domain.OutboxEvent;
import com.bankcore.messaging.infrastructure.OutboxEventRepository;

@Service
@ConditionalOnProperty(prefix = "bankcore.messaging", name = "enabled", havingValue = "true")
public class OutboxClaimService {

    private final OutboxEventRepository repository;
    private final MessagingProperties properties;
    private final Clock clock;

    public OutboxClaimService(OutboxEventRepository repository,
                              MessagingProperties properties,
                              Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> events = repository.findPublishableForUpdate(
                now,
                now.minus(properties.processingTimeout()),
                properties.maxPublishAttempts(),
                properties.publisherBatchSize());
        events.forEach(event -> event.claim(now));
        repository.flush();
        return List.copyOf(events);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        OutboxEvent event = repository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new IllegalStateException("Claimed outbox event is missing"));
        event.markPublished(Instant.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID eventId, Throwable failure) {
        OutboxEvent event = repository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new IllegalStateException("Claimed outbox event is missing"));
        String safeError = "Kafka publication failed: " + failure.getClass().getSimpleName();
        event.markPublishFailed(
                Instant.now(clock),
                properties.maxPublishAttempts(),
                properties.retryDelay(),
                safeError);
    }
}
