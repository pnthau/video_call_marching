package com.example.videocall_marching_language.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Establishes a server-owned request correlation ID for each servlet dispatch. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    private static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".effectiveId";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = (String) request.getAttribute(REQUEST_ATTRIBUTE);
        if (correlationId == null) {
            correlationId = newCorrelationId();
            request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        }

        withCorrelationContext(correlationId, request, response, filterChain);
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request,
                                               HttpServletResponse response,
                                               FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = (String) request.getAttribute(REQUEST_ATTRIBUTE);
        if (correlationId == null) {
            correlationId = newCorrelationId();
            request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        }
        withCorrelationContext(correlationId, request, response, filterChain);
    }

    private void withCorrelationContext(String correlationId,
                                        HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HEADER_NAME, correlationId);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    private String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
