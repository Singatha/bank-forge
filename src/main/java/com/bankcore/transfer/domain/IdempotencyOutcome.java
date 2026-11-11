package com.bankcore.transfer.domain;

public enum IdempotencyOutcome {
    PROCESSING,
    COMPLETED,
    FAILED
}
