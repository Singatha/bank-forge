package com.bankcore.transfer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyOutcome outcome;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID id, UUID userId, String idempotencyKey,
                             String requestHash, Instant now) {
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.outcome = IdempotencyOutcome.PROCESSING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public IdempotencyOutcome getOutcome() {
        return outcome;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void complete(UUID completedTransferId, Instant now) {
        transferId = completedTransferId;
        outcome = IdempotencyOutcome.COMPLETED;
        responseStatus = 201;
        updatedAt = now;
    }

    public void fail(UUID failedTransferId, int status, String code, String message, Instant now) {
        transferId = failedTransferId;
        outcome = IdempotencyOutcome.FAILED;
        responseStatus = status;
        errorCode = code;
        errorMessage = message;
        updatedAt = now;
    }
}
