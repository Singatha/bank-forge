package com.bankcore.notification.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bankcore.notification.domain.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    @Modifying
    @Query(value = """
            insert into processed_events (id, consumer_name, event_id, processed_at)
            values (:id, :consumerName, :eventId, :processedAt)
            on conflict (consumer_name, event_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("consumerName") String consumerName,
            @Param("eventId") UUID eventId,
            @Param("processedAt") Instant processedAt);
}
