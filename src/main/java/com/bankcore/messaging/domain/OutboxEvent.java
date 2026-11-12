package com.bankcore.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private EventType eventType;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, String> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, EventType eventType, UUID aggregateId, Instant occurredAt,
                       UUID correlationId, Map<String, String> payload) {
        this.id = id;
        this.eventType = eventType;
        this.aggregateType = "TRANSFER";
        this.aggregateId = aggregateId;
        this.occurredAt = occurredAt;
        this.correlationId = correlationId;
        this.payload = Map.copyOf(payload);
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = occurredAt;
        this.createdAt = occurredAt;
        this.updatedAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public EventEnvelope envelope() {
        return new EventEnvelope(id, eventType, aggregateType, aggregateId,
                occurredAt, correlationId, payload);
    }

    public void claim(Instant now) {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.PROCESSING) {
            throw new IllegalStateException("Only publishable outbox events can be claimed.");
        }
        if (status == OutboxStatus.PENDING) {
            attempts++;
        }
        status = OutboxStatus.PROCESSING;
        lockedAt = now;
        updatedAt = now;
    }

    public void markPublished(Instant now) {
        requireProcessing();
        status = OutboxStatus.PUBLISHED;
        lockedAt = null;
        nextAttemptAt = null;
        publishedAt = now;
        lastError = null;
        updatedAt = now;
    }

    public void markPublishFailed(Instant now, int maxAttempts, Duration retryDelay, String safeError) {
        requireProcessing();
        lockedAt = null;
        lastError = safeError;
        updatedAt = now;
        if (attempts >= maxAttempts) {
            status = OutboxStatus.FAILED;
            nextAttemptAt = null;
        } else {
            status = OutboxStatus.PENDING;
            nextAttemptAt = now.plus(retryDelay.multipliedBy(attempts));
        }
    }

    private void requireProcessing() {
        if (status != OutboxStatus.PROCESSING) {
            throw new IllegalStateException("Outbox event must be processing.");
        }
    }
}
