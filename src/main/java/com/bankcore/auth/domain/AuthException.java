package com.bankcore.auth.domain;

import org.springframework.http.HttpStatus;

import com.bankcore.shared.error.ApiException;

public final class AuthException extends ApiException {

    private AuthException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static AuthException emailAlreadyRegistered() {
        return new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "An account with this email address already exists.");
    }

    public static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "The email address or password is incorrect.");
    }

    public static AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "The refresh token is invalid or expired.");
    }

    public static AuthException refreshTokenReused() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                "Refresh token reuse was detected and the session was revoked.");
    }

    public static AuthException accountDisabled() {
        return new AuthException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED",
                "This account is disabled.");
    }

    public static AuthException invalidPasswordLength() {
        return new AuthException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_LENGTH",
                "The password must not exceed 72 UTF-8 bytes.");
    }
}

