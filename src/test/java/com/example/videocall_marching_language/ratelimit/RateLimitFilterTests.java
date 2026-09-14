package com.example.videocall_marching_language.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class RateLimitFilterTests {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    private final RateLimitFilter filter = new RateLimitFilter(limiter, new RateLimitRequestResolver());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesAuthenticatedPrincipalAndAllowsWithinQuota() throws Exception {
        authenticate("alice@example.test");
        MockHttpServletRequest request = request("GET", "/api/sessions/active");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void exhaustionReturnsSanitizedJson429AndDoesNotInvokeDownstream() throws Exception {
        authenticate("alice@example.test");
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < RateLimitPolicy.SESSION_ACTIVE.capacity(); i++) {
            filter.doFilterInternal(request("GET", "/api/sessions/active"), new MockHttpServletResponse(), chain);
        }
        assertFalse(limiter.tryAcquire(RateLimitPolicy.SESSION_ACTIVE, "alice@example.test"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request("GET", "/api/sessions/active"), response, chain);

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) > 0);
        assertNotNull(response.getContentAsString());
        assertFalse(response.getContentAsString().contains("alice@example.test"));
        assertFalse(response.getContentAsString().contains("/api/sessions/secret-session/active"));
        assertTrue(response.getContentAsString().contains("/api/sessions/**"));
        verify(chain, never()).doFilter(request("GET", "/api/sessions/active"), response);
    }

    @Test
    void profileAndAdminExhaustionUseStaticPaths() throws Exception {
        authenticate("admin@example.test");
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < RateLimitPolicy.AVATAR_UPLOAD.capacity(); i++) {
            MockHttpServletRequest upload = request("POST", "/profile/edit");
            upload.setContentType("multipart/form-data; boundary=test");
            filter.doFilterInternal(upload, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest upload = request("POST", "/profile/edit");
        upload.setContentType("multipart/form-data; boundary=test");
        MockHttpServletResponse profileResponse = new MockHttpServletResponse();
        filter.doFilterInternal(upload, profileResponse, chain);
        assertEquals(429, profileResponse.getStatus());
        assertTrue(profileResponse.getContentAsString().contains("/profile/edit"));
        assertFalse(profileResponse.getContentAsString().contains("admin@example.test"));

        for (int i = 0; i < RateLimitPolicy.ADMIN_MUTATION.capacity(); i++) {
            filter.doFilterInternal(request("POST", "/admin/users/42/delete"),
                    new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();
        filter.doFilterInternal(request("POST", "/admin/users/42/delete"), adminResponse, chain);
        assertEquals(429, adminResponse.getStatus());
        assertTrue(adminResponse.getContentAsString().contains("/admin/**"));
        assertFalse(adminResponse.getContentAsString().contains("/42/"));
    }

    @Test
    void tokenExhaustionUsesStaticSessionScopeAndRemainingRetryTime() throws Exception {
        authenticate("token@example.test");
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < RateLimitPolicy.SESSION_TOKEN.capacity(); i++) {
            MockHttpServletRequest tokenRequest = request("GET", "/api/sessions/secret-id/token");
            tokenRequest.setQueryString("filename=private");
            filter.doFilterInternal(tokenRequest,
                    new MockHttpServletResponse(), chain);
        }
        assertTrue(limiter.retryAfterSeconds(RateLimitPolicy.SESSION_TOKEN, "token@example.test") > 0);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest tokenRequest = request("GET", "/api/sessions/secret-id/token");
        tokenRequest.setQueryString("filename=private");
        filter.doFilterInternal(tokenRequest, response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("/api/sessions/**", extractPath(response.getContentAsString()));
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) > 0);
        assertFalse(response.getContentAsString().contains("secret-id"));
        assertFalse(response.getContentAsString().contains("filename"));
    }

    @Test
    void joinAgoraEleventhRequestReturns429WithoutDownstreamInvocation() throws Exception {
        authenticate("join-agora@example.test");
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < RateLimitPolicy.SESSION_JOIN.capacity(); i++) {
            filter.doFilterInternal(request("POST", "/api/sessions/42/join-agora"),
                    new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest request = request("POST", "/api/sessions/42/join-agora");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertEquals(429, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void leaveAgoraEleventhRequestReturns429WithoutDownstreamInvocation() throws Exception {
        authenticate("leave-agora@example.test");
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < RateLimitPolicy.SESSION_LEAVE.capacity(); i++) {
            filter.doFilterInternal(request("POST", "/api/sessions/42/leave-agora"),
                    new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest request = request("POST", "/api/sessions/42/leave-agora");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertEquals(429, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void filterRetryAfterUsesBucketRemainingTimeAfterPartialRefill() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryRateLimiter controlledLimiter = new InMemoryRateLimiter(new java.time.Clock() {
            public Instant instant() { return now.get(); }
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        });
        RateLimitFilter controlledFilter = new RateLimitFilter(controlledLimiter, new RateLimitRequestResolver());
        authenticate("remaining@example.test");
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < RateLimitPolicy.SESSION_TOKEN.capacity(); i++) {
            controlledFilter.doFilterInternal(request("GET", "/api/sessions/1/token"),
                    new MockHttpServletResponse(), chain);
        }
        now.set(now.get().plusSeconds(30));
        for (int i = 0; i < 5; i++) {
            controlledFilter.doFilterInternal(request("GET", "/api/sessions/1/token"),
                    new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        controlledFilter.doFilterInternal(request("GET", "/api/sessions/1/token"), response, chain);
        assertEquals(429, response.getStatus());
        long retryAfter = Long.parseLong(response.getHeader("Retry-After"));
        assertTrue(retryAfter > 0);
        assertTrue(retryAfter < RateLimitPolicy.SESSION_TOKEN.window().toSeconds());
    }

    private static String extractPath(String body) {
        int start = body.indexOf("\"path\":\"") + 8;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    @Test
    void anonymousRequestsAreNotConvertedIntoRateLimitResponses() throws Exception {
        SecurityContextHolder.clearContext();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("GET", "/api/sessions/active");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        request.setServletPath(path);
        return request;
    }

    private static void authenticate(String principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "N/A", List.of()));
    }
}
