package com.bankcore.shared.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class RequestContext {

    public RequestIdentifiers identifiers() {
        UUID requestId = attribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        UUID correlationId = optionalAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE);
        return new RequestIdentifiers(requestId, correlationId == null ? requestId : correlationId);
    }

    private UUID attribute(String name) {
        UUID value = optionalAttribute(name);
        return value == null ? UUID.randomUUID() : value;
    }

    private UUID optionalAttribute(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object value = request.getAttribute(name);
            if (value instanceof UUID uuid) {
                return uuid;
            }
        }
        return null;
    }

    public record RequestIdentifiers(UUID requestId, UUID correlationId) {
    }
}
