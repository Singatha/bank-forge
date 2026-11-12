package com.bankcore.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    private UUID id;

    @Column(name = "consumer_name", nullable = false, updatable = false, length = 100)
    private String consumerName;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID id, String consumerName, UUID eventId, Instant processedAt) {
        this.id = id;
        this.consumerName = consumerName;
        this.eventId = eventId;
        this.processedAt = processedAt;
    }
}
