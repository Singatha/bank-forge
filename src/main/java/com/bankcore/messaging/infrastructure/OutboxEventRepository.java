package com.bankcore.messaging.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bankcore.messaging.domain.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            select *
            from outbox_events
            where (status = 'PENDING' and attempts < :maxAttempts and next_attempt_at <= :now)
               or (status = 'PROCESSING' and locked_at <= :staleBefore)
            order by created_at
            for update skip locked
            limit :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> findPublishableForUpdate(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from OutboxEvent event where event.id = :eventId")
    Optional<OutboxEvent> findByIdForUpdate(@Param("eventId") UUID eventId);
}
