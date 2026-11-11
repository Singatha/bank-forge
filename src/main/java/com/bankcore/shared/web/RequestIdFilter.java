package com.bankcore.shared.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String CORRELATION_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".correlationId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        UUID requestId = parseOrGenerate(request.getHeader(REQUEST_ID_HEADER));
        UUID correlationId = parseOrDefault(request.getHeader(CORRELATION_ID_HEADER), requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId.toString());
        response.setHeader(CORRELATION_ID_HEADER, correlationId.toString());

        long startedAt = System.nanoTime();
        MDC.put(REQUEST_ID_MDC_KEY, requestId.toString());
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOGGER.info("http_request method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove(REQUEST_ID_MDC_KEY);
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    private UUID parseOrGenerate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
        }
    }

    private UUID parseOrDefault(String candidate, UUID defaultValue) {
        if (candidate == null || candidate.isBlank()) {
            return defaultValue;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
        }
    }
}

