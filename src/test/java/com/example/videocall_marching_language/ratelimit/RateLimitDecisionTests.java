package com.example.videocall_marching_language.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitDecisionTests {
    @Test
    void deniedDecisionExposesRemainingPermitSeconds() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < RateLimitPolicy.SESSION_TOKEN.capacity(); i++) {
            assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "decision"));
        }
        RateLimitDecision decision = limiter.tryAcquireDecision(RateLimitPolicy.SESSION_TOKEN, "decision");
        assertFalse(decision.allowed());
        assertTrue(decision.retryAfterSeconds() > 0);
    }
}
