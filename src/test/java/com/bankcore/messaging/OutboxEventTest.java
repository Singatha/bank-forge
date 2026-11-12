package com.bankcore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bankcore.messaging.domain.EventType;
import com.bankcore.messaging.domain.OutboxEvent;
import com.bankcore.messaging.domain.OutboxStatus;

class OutboxEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void retriesWithBackoffAndStopsAtTheConfiguredAttemptLimit() {
        OutboxEvent event = event();

        event.claim(OCCURRED_AT);
        event.markPublishFailed(OCCURRED_AT, 2, Duration.ofSeconds(5), "broker unavailable");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(OCCURRED_AT.plusSeconds(5));

        event.claim(OCCURRED_AT.plusSeconds(5));
        event.markPublishFailed(OCCURRED_AT.plusSeconds(5), 2, Duration.ofSeconds(5), "broker unavailable");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("broker unavailable");
    }

    @Test
    void recoveringAStaleClaimDoesNotConsumeAnotherAttempt() {
        OutboxEvent event = event();

        event.claim(OCCURRED_AT);
        event.claim(OCCURRED_AT.plusSeconds(60));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void marksAClaimedEventAsPublished() {
        OutboxEvent event = event();
        Instant publishedAt = OCCURRED_AT.plusSeconds(1);

        event.claim(OCCURRED_AT);
        event.markPublished(publishedAt);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void rejectsTerminalStateTransitions() {
        OutboxEvent event = event();
        event.claim(OCCURRED_AT);
        event.markPublished(OCCURRED_AT.plusSeconds(1));

        assertThatThrownBy(() -> event.claim(OCCURRED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.markPublishFailed(
                OCCURRED_AT.plusSeconds(2), 2, Duration.ofSeconds(5), "late failure"))
                .isInstanceOf(IllegalStateException.class);
    }

    private OutboxEvent event() {
        return new OutboxEvent(
                UUID.randomUUID(),
                EventType.TRANSFER_COMPLETED,
                UUID.randomUUID(),
                OCCURRED_AT,
                UUID.randomUUID(),
                Map.of("reference", "TR123"));
    }
}
