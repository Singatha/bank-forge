package com.bankcore.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void preservesValidClientRequestId() throws Exception {
        UUID requestId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(requestId);
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(requestId.toString());
        assertThat(request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)).isEqualTo(requestId);
        assertThat(response.getHeader(RequestIdFilter.CORRELATION_ID_HEADER)).isEqualTo(requestId.toString());
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(RequestIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesInvalidClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generatedId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(generatedId).isNotNull();
        assertThat(UUID.fromString(generatedId)).isInstanceOf(UUID.class);
    }

    @Test
    void preservesIndependentCorrelationId() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, requestId.toString());
        request.addHeader(RequestIdFilter.CORRELATION_ID_HEADER, correlationId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)).isEqualTo(correlationId);
        assertThat(response.getHeader(RequestIdFilter.CORRELATION_ID_HEADER)).isEqualTo(correlationId.toString());
    }
}

