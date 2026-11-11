package com.bankcore.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bankcore.ledger.application.AccountTransaction;

public record AccountTransactionResponse(
        UUID id,
        UUID transferId,
        String transferReference,
        UUID accountId,
        String entryType,
        BigDecimal amount,
        String currency,
        String description,
        Instant createdAt) {

    static AccountTransactionResponse from(AccountTransaction transaction) {
        return new AccountTransactionResponse(
                transaction.id(),
                transaction.transferId(),
                transaction.transferReference(),
                transaction.accountId(),
                transaction.entryType().name(),
                transaction.amount(),
                transaction.currency().name(),
                transaction.description(),
                transaction.createdAt());
    }
}
