package com.example.videocall_marching_language.ratelimit;

import com.example.videocall_marching_language.dto.response.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

public final class RateLimitFilter extends OncePerRequestFilter {
    private static final String SAFE_MESSAGE = "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.";

    private final InMemoryRateLimiter limiter;
    private final RateLimitRequestResolver resolver;

    public RateLimitFilter(InMemoryRateLimiter limiter, RateLimitRequestResolver resolver) {
        this.limiter = limiter;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean avatarMultipart = request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith("multipart/form-data");
        Optional<RateLimitPolicy> policy = resolver.resolve(request.getMethod(), request.getRequestURI(), avatarMultipart);
        if (policy.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        RateLimitDecision decision = limiter.tryAcquireDecision(policy.get(), authentication.getName());
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        long retryAfter = decision.retryAfterSeconds();
        response.setHeader("Retry-After", Long.toString(Math.max(1L, retryAfter)));
        String path = safePath(policy.get(), request.getRequestURI());
        ApiErrorResponse body = new ApiErrorResponse("RATE_LIMIT_EXCEEDED", SAFE_MESSAGE, Instant.now(), path);
        response.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\""
                + escapeJson(body.message()) + "\",\"timestamp\":\"" + body.timestamp()
                + "\",\"path\":\"" + escapeJson(body.path()) + "\"}");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safePath(RateLimitPolicy policy, String requestUri) {
        if (policy == RateLimitPolicy.AVATAR_UPLOAD) {
            return "/profile/edit";
        }
        if (policy == RateLimitPolicy.ADMIN_MUTATION) {
            return "/admin/**";
        }
        return "/api/sessions/**";
    }
}
