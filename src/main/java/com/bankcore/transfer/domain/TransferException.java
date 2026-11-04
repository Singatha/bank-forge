package com.bankcore.transfer.domain;

import org.springframework.http.HttpStatus;

import com.bankcore.shared.error.ApiException;

public final class TransferException extends ApiException {

    private TransferException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static TransferException transferNotFound() {
        return new TransferException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                "The transfer was not found.");
    }

    public static TransferException sameAccount() {
        return new TransferException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER",
                "Source and destination accounts must be different.");
    }

    public static TransferException invalidStateTransition(TransferStatus from, TransferStatus to) {
        return new TransferException(HttpStatus.CONFLICT, "INVALID_TRANSFER_STATE",
                "A transfer cannot transition from " + from + " to " + to + ".");
    }

    public static TransferException businessFailure(ApiException cause) {
        return new TransferException(cause.status(), cause.code(), cause.getMessage());
    }
}
