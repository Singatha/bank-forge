package com.bankcore.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, updatable = false, length = 120)
    private String title;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(UUID id, UUID eventId, UUID transferId, UUID customerId,
                        NotificationType type, String title, String message, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.transferId = transferId;
        this.customerId = customerId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.read = false;
        this.createdAt = createdAt;
    }
}
