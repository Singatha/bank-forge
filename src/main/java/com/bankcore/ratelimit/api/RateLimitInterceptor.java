package com.bankcore.ratelimit.api;

import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.bankcore.config.RateLimitProperties;
import com.bankcore.ratelimit.application.RateLimitService;
import com.bankcore.ratelimit.domain.RateLimitDecision;
import com.bankcore.ratelimit.domain.RateLimitException;

@Component
@ConditionalOnProperty(prefix = "bankcore.rate-limiting", name = "enabled", havingValue = "true")
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String RESET_HEADER = "X-RateLimit-Reset";
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                RateLimitProperties properties,
                                Clock clock) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        PolicyRequest policyRequest = policy(request);
        if (policyRequest == null) {
            return true;
        }

        RateLimitDecision decision = rateLimitService.check(
                policyRequest.name(), policyRequest.policy(), policyRequest.identifier());
        response.setHeader(LIMIT_HEADER, Integer.toString(decision.limit()));
        response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
        long resetAt = Instant.now(clock).plus(decision.resetAfter()).getEpochSecond();
        response.setHeader(RESET_HEADER, Long.toString(resetAt));
        if (!decision.allowed()) {
            long retryAfter = Math.max(1L, (decision.resetAfter().toMillis() + 999L) / 1000L);
            response.setHeader(RETRY_AFTER_HEADER, Long.toString(retryAfter));
            throw RateLimitException.exceeded();
        }
        return true;
    }

    private PolicyRequest policy(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI();
        if (path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/refresh")) {
            return new PolicyRequest("authentication", properties.authentication(), request.getRemoteAddr());
        }
        if (path.equals("/api/v1/transfers")) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                return new PolicyRequest("transfers", properties.transfers(), authentication.getName());
            }
        }
        return null;
    }

    private record PolicyRequest(
            String name,
            RateLimitProperties.Policy policy,
            String identifier) {
    }
}
