package com.bankcore.account.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.bankcore.account.domain.Account;

public record AccountBalanceResponse(
        UUID accountId,
        String currency,
        BigDecimal availableBalance,
        String status,
        long version) {

    public static AccountBalanceResponse from(Account account) {
        return new AccountBalanceResponse(
                account.getId(),
                account.getCurrency().name(),
                account.getAvailableBalance(),
                account.getStatus().name(),
                account.getVersion());
    }
}
