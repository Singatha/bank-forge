package com.bankcore.account.api;

import jakarta.validation.constraints.NotNull;

import com.bankcore.account.domain.AccountType;
import com.bankcore.account.domain.Currency;

public record CreateAccountRequest(
        @NotNull AccountType accountType,
        @NotNull Currency currency) {
}
