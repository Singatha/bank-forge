package com.bankcore.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bankcore.account.domain.Currency;
import com.bankcore.ledger.domain.EntryType;

public record AccountTransaction(
        UUID id,
        UUID transferId,
        String transferReference,
        UUID accountId,
        EntryType entryType,
        BigDecimal amount,
        Currency currency,
        String description,
        Instant createdAt) {
}
