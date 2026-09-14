package com.example.videocall_marching_language.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitPolicyTests {

    @Test
    void approvedQuotasAndWindowsAreStable() {
        assertEquals(10, RateLimitPolicy.SESSION_TOKEN.capacity());
        assertEquals(30, RateLimitPolicy.SESSION_ACTIVE.capacity());
        assertEquals(10, RateLimitPolicy.SESSION_JOIN.capacity());
        assertEquals(10, RateLimitPolicy.SESSION_LEAVE.capacity());
        assertEquals(5, RateLimitPolicy.AVATAR_UPLOAD.capacity());
        assertEquals(30, RateLimitPolicy.ADMIN_MUTATION.capacity());

        assertEquals(Duration.ofMinutes(1), RateLimitPolicy.SESSION_TOKEN.window());
        assertEquals(Duration.ofMinutes(1), RateLimitPolicy.SESSION_ACTIVE.window());
        assertEquals(Duration.ofMinutes(1), RateLimitPolicy.SESSION_JOIN.window());
        assertEquals(Duration.ofMinutes(1), RateLimitPolicy.SESSION_LEAVE.window());
        assertEquals(Duration.ofMinutes(10), RateLimitPolicy.AVATAR_UPLOAD.window());
        assertEquals(Duration.ofMinutes(1), RateLimitPolicy.ADMIN_MUTATION.window());
    }
}
