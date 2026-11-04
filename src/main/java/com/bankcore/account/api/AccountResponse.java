package com.bankcore.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.bankcore.account.domain.Account;

public record AccountResponse(
        UUID id,
        String accountNumber,
        UUID customerId,
        String accountType,
        String currency,
        BigDecimal availableBalance,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getAccountType().name(),
                account.getCurrency().name(),
                account.getAvailableBalance(),
                account.getStatus().name(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
