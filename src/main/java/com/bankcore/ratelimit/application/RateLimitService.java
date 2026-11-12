package com.bankcore.ratelimit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.bankcore.config.RateLimitProperties;
import com.bankcore.observability.application.BankCoreMetrics;
import com.bankcore.ratelimit.domain.RateLimitDecision;
import com.bankcore.ratelimit.domain.RateLimitException;

@Service
@ConditionalOnProperty(prefix = "bankcore.rate-limiting", name = "enabled", havingValue = "true")
public class RateLimitService {

    private static final DefaultRedisScript<List> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {count, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final BankCoreMetrics metrics;

    public RateLimitService(StringRedisTemplate redisTemplate,
                            RateLimitProperties properties,
                            BankCoreMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public RateLimitDecision check(String policyName,
                                   RateLimitProperties.Policy policy,
                                   String identifier) {
        validatePolicy(policyName, policy);
        String key = properties.keyPrefix() + ":" + policyName + ":" + digest(identifier);
        try {
            List<?> result = redisTemplate.execute(
                    FIXED_WINDOW_SCRIPT,
                    List.of(key),
                    Long.toString(policy.window().toMillis()));
            if (result == null || result.size() != 2) {
                metrics.rateLimitDecision(policyName, "unavailable");
                throw RateLimitException.unavailable();
            }
            long count = number(result.get(0));
            long ttlMillis = Math.max(0L, number(result.get(1)));
            boolean allowed = count <= policy.limit();
            metrics.rateLimitDecision(policyName, allowed ? "allowed" : "rejected");
            return new RateLimitDecision(
                    allowed,
                    policy.limit(),
                    Math.max(0L, policy.limit() - count),
                    Duration.ofMillis(ttlMillis));
        } catch (DataAccessException exception) {
            metrics.rateLimitDecision(policyName, "unavailable");
            throw RateLimitException.unavailable();
        }
    }

    private void validatePolicy(String policyName, RateLimitProperties.Policy policy) {
        if (policy == null || policy.limit() <= 0 || policy.window() == null
                || policy.window().isZero() || policy.window().isNegative()) {
            throw new IllegalStateException("Invalid rate-limit policy: " + policyName);
        }
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw RateLimitException.unavailable();
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
