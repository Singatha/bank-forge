package com.bankcore.account.domain;

import org.springframework.http.HttpStatus;

import com.bankcore.shared.error.ApiException;

public final class AccountException extends ApiException {

    private AccountException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static AccountException accountNotFound() {
        return new AccountException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                "The account was not found.");
    }

    public static AccountException accountNotActive() {
        return new AccountException(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE",
                "The account must be active for this operation.");
    }

    public static AccountException currencyMismatch() {
        return new AccountException(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH",
                "The funding currency must match the account currency.");
    }

    public static AccountException invalidFundingAmount() {
        return new AccountException(HttpStatus.BAD_REQUEST, "INVALID_FUNDING_AMOUNT",
                "The funding amount must be positive and have at most two decimal places.");
    }
}
