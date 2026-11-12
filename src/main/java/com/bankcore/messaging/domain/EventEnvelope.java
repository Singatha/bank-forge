package com.bankcore.messaging.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        EventType eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        UUID correlationId,
        Map<String, String> payload) {

    public EventEnvelope {
        payload = Map.copyOf(payload);
    }
}
