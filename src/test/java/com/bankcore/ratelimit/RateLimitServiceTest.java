package com.bankcore.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import com.bankcore.config.RateLimitProperties;
import com.bankcore.observability.application.BankCoreMetrics;
import com.bankcore.ratelimit.application.RateLimitService;
import com.bankcore.ratelimit.domain.RateLimitException;

class RateLimitServiceTest {

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        BankCoreMetrics metrics = mock(BankCoreMetrics.class);
        RateLimitProperties.Policy policy = new RateLimitProperties.Policy(10, Duration.ofMinutes(1));
        RateLimitProperties properties = new RateLimitProperties(
                true, "bankcore:test", policy, policy);
        RateLimitService service = new RateLimitService(redisTemplate, properties, metrics);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis is unavailable"));

        assertThatThrownBy(() -> service.check("authentication", policy, "127.0.0.1"))
                .isInstanceOfSatisfying(RateLimitException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("RATE_LIMIT_UNAVAILABLE");
                });
        verify(metrics).rateLimitDecision("authentication", "unavailable");
    }
}
