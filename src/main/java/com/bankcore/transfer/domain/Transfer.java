package com.bankcore.transfer.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.bankcore.account.domain.Currency;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 24)
    private String reference;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(name = "initiated_by_user_id", nullable = false)
    private UUID initiatedByUserId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(length = 140)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    protected Transfer() {
    }

    public Transfer(UUID id, String reference, UUID sourceAccountId, UUID destinationAccountId,
                    UUID initiatedByUserId, BigDecimal amount, Currency currency,
                    String description, Instant createdAt) {
        this.id = id;
        this.reference = reference;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.initiatedByUserId = initiatedByUserId;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.currency = currency;
        this.status = TransferStatus.PENDING;
        this.description = description == null || description.isBlank() ? null : description.strip();
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public UUID getInitiatedByUserId() {
        return initiatedByUserId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void startProcessing() {
        transitionTo(TransferStatus.PROCESSING);
    }

    public void complete(Instant now) {
        transitionTo(TransferStatus.COMPLETED);
        completedAt = now;
    }

    public void fail(String reason) {
        transitionTo(TransferStatus.FAILED);
        failureReason = reason;
    }

    public void reverse() {
        transitionTo(TransferStatus.REVERSED);
    }

    private void transitionTo(TransferStatus target) {
        boolean valid = switch (status) {
            case PENDING -> target == TransferStatus.PROCESSING;
            case PROCESSING -> target == TransferStatus.COMPLETED || target == TransferStatus.FAILED;
            case COMPLETED -> target == TransferStatus.REVERSED;
            case FAILED, REVERSED -> false;
        };
        if (!valid) {
            throw TransferException.invalidStateTransition(status, target);
        }
        status = target;
    }
}
