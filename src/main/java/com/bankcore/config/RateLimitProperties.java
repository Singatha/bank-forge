package com.bankcore.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bankcore.rate-limiting")
public record RateLimitProperties(
        boolean enabled,
        String keyPrefix,
        Policy authentication,
        Policy transfers) {

    public record Policy(int limit, Duration window) {
    }
}
