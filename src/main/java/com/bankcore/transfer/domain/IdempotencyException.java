package com.bankcore.transfer.domain;

import org.springframework.http.HttpStatus;

import com.bankcore.shared.error.ApiException;

public final class IdempotencyException extends ApiException {

    private IdempotencyException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static IdempotencyException invalidKey() {
        return new IdempotencyException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must contain 1 to 128 supported characters.");
    }

    public static IdempotencyException keyReused() {
        return new IdempotencyException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "The idempotency key was already used for a different request.");
    }

    public static IdempotencyException processing() {
        return new IdempotencyException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "The original request is still being processed.");
    }
}
