package com.bankcore.auth.api;

import java.time.Duration;
import java.time.Instant;

import com.bankcore.auth.application.TokenPair;

public record TokenResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        String refreshToken) {

    public static TokenResponse from(TokenPair tokenPair, Instant now) {
        long expiresIn = Math.max(0, Duration.between(now, tokenPair.accessTokenExpiresAt()).toSeconds());
        return new TokenResponse("Bearer", tokenPair.accessToken(), expiresIn, tokenPair.refreshToken());
    }
}

