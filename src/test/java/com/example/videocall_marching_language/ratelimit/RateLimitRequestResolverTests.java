package com.example.videocall_marching_language.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitRequestResolverTests {

    private final RateLimitRequestResolver resolver = new RateLimitRequestResolver();

    @Test
    void resolvesSessionEndpointsWithActiveBeforePathVariable() {
        assertPolicy("GET", "/api/sessions/active", false, RateLimitPolicy.SESSION_ACTIVE);
        assertPolicy("GET", "/api/sessions/42/token", false, RateLimitPolicy.SESSION_TOKEN);
        assertPolicy("POST", "/api/sessions/42/join", false, RateLimitPolicy.SESSION_JOIN);
        assertPolicy("POST", "/api/sessions/42/leave", false, RateLimitPolicy.SESSION_LEAVE);
        assertPolicy("POST", "/api/sessions/42/join-agora", false, RateLimitPolicy.SESSION_JOIN);
        assertPolicy("POST", "/api/sessions/42/leave-agora", false, RateLimitPolicy.SESSION_LEAVE);
    }

    @Test
    void resolvesOnlyAvatarMultipartOnProfileEdit() {
        assertPolicy("POST", "/profile/edit", true, RateLimitPolicy.AVATAR_UPLOAD);
        assertEquals(Optional.empty(), resolver.resolve("POST", "/profile/edit", false));
        assertEquals(Optional.empty(), resolver.resolve("GET", "/profile/edit", true));
    }

    @Test
    void resolvesAdminMutationsButNotReads() {
        assertPolicy("POST", "/admin/rubrics/7/edit", false, RateLimitPolicy.ADMIN_MUTATION);
        assertPolicy("POST", "/admin/users/add", false, RateLimitPolicy.ADMIN_MUTATION);
        assertPolicy("DELETE", "/admin/users/7", false, RateLimitPolicy.ADMIN_MUTATION);
        assertEquals(Optional.empty(), resolver.resolve("GET", "/admin/rubrics", false));
    }

    @Test
    void ignoresUnknownAndMalformedPaths() {
        assertEquals(Optional.empty(), resolver.resolve("GET", "/api/sessions", false));
        assertEquals(Optional.empty(), resolver.resolve("POST", "/api/sessions/abc/unknown", false));
        assertEquals(Optional.empty(), resolver.resolve("POST", null, false));
    }

    private void assertPolicy(String method, String path, boolean avatar, RateLimitPolicy expected) {
        Optional<RateLimitPolicy> actual = resolver.resolve(method, path, avatar);
        assertTrue(actual.isPresent());
        assertEquals(expected, actual.get());
    }
}
