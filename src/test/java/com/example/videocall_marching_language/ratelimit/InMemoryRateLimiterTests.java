package com.example.videocall_marching_language.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRateLimiterTests {

    @Test
    void consumesCapacityThenRefillsFromInjectedClock() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = mutableClock(now);
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

        IntStream.range(0, RateLimitPolicy.SESSION_JOIN.capacity())
                .forEach(i -> assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_JOIN, "alice")));
        assertFalse(limiter.tryAcquire(RateLimitPolicy.SESSION_JOIN, "alice"));

        now.set(now.get().plus(RateLimitPolicy.SESSION_JOIN.window()));
        assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_JOIN, "alice"));
    }

    @Test
    void separatesPrincipalsAndPolicies() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        IntStream.range(0, RateLimitPolicy.SESSION_TOKEN.capacity())
                .forEach(i -> assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "alice")));
        assertFalse(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "alice"));
        assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "bob"));
        assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_ACTIVE, "alice"));
    }

    @Test
    void boundsRegistryAndExpiresBucketsAfterAccess() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(mutableClock(now), 2, Duration.ofMinutes(5));

        assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "alice"));
        assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "bob"));
        assertEquals(2, limiter.bucketCount());
        assertFalse(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "carol"));
        assertEquals(2, limiter.bucketCount());

        now.set(now.get().plus(Duration.ofMinutes(6)));
        limiter.cleanupExpired();
        assertEquals(0, limiter.bucketCount());
    }

    @Test
    void deniedDecisionAfterPartialRefillUsesRemainingTimeNotFullWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(mutableClock(now));
        for (int i = 0; i < RateLimitPolicy.SESSION_TOKEN.capacity(); i++) {
            assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "partial"));
        }
        now.set(now.get().plusSeconds(30));
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "partial"));
        }
        assertFalse(limiter.tryAcquire(RateLimitPolicy.SESSION_TOKEN, "partial"));
        long retryAfter = limiter.retryAfterSeconds(RateLimitPolicy.SESSION_TOKEN, "partial");
        assertTrue(retryAfter > 0);
        assertTrue(retryAfter < RateLimitPolicy.SESSION_TOKEN.window().toSeconds());
    }

    private static Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
    }
}
