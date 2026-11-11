package com.bankcore.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import com.bankcore.account.domain.Currency;

@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 10)
    private EntryType entryType;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(UUID id, UUID transferId, UUID accountId, EntryType entryType,
                       BigDecimal amount, Currency currency, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.transferId = Objects.requireNonNull(transferId);
        this.accountId = Objects.requireNonNull(accountId);
        this.entryType = Objects.requireNonNull(entryType);
        this.amount = Objects.requireNonNull(amount).setScale(2, RoundingMode.UNNECESSARY);
        this.currency = Objects.requireNonNull(currency);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (this.amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be positive.");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
