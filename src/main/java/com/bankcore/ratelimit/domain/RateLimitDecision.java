package com.bankcore.ratelimit.domain;

import java.time.Duration;

public record RateLimitDecision(
        boolean allowed,
        int limit,
        long remaining,
        Duration resetAfter) {
}
