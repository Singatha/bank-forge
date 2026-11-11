package com.bankcore.transfer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.transfer.domain.IdempotencyException;
import com.bankcore.transfer.domain.IdempotencyRecord;
import com.bankcore.transfer.infrastructure.IdempotencyLock;
import com.bankcore.transfer.infrastructure.IdempotencyRecordRepository;

@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final IdempotencyRecordRepository repository;
    private final IdempotencyLock lock;
    private final Clock clock;

    public IdempotencyService(IdempotencyRecordRepository repository, IdempotencyLock lock, Clock clock) {
        this.repository = repository;
        this.lock = lock;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyAttempt begin(UUID userId, String key, String requestHash) {
        validateKey(key);

        lock.acquire(userId + ":" + key);
        return repository.findByUserIdAndIdempotencyKey(userId, key)
                .map(existing -> existingAttempt(existing, requestHash))
                .orElseGet(() -> newAttempt(userId, key, requestHash));
    }

    public void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw IdempotencyException.invalidKey();
        }
    }

    private IdempotencyAttempt existingAttempt(IdempotencyRecord existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw IdempotencyException.keyReused();
        }
        return new IdempotencyAttempt(existing, true);
    }

    private IdempotencyAttempt newAttempt(UUID userId, String key, String requestHash) {
        IdempotencyRecord record = new IdempotencyRecord(
                UUID.randomUUID(), userId, key, requestHash, Instant.now(clock));
        return new IdempotencyAttempt(repository.saveAndFlush(record), false);
    }

    public record IdempotencyAttempt(IdempotencyRecord record, boolean replayed) {
    }
}
