package com.bankcore.ratelimit.domain;

import org.springframework.http.HttpStatus;

import com.bankcore.shared.error.ApiException;

public class RateLimitException extends ApiException {

    private RateLimitException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static RateLimitException exceeded() {
        return new RateLimitException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                "Too many requests. Try again later.");
    }

    public static RateLimitException unavailable() {
        return new RateLimitException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RATE_LIMIT_UNAVAILABLE",
                "Request rate limiting is temporarily unavailable.");
    }
}
