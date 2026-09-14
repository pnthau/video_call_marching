package com.example.videocall_marching_language.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public final class InMemoryRateLimiter {
    private static final int DEFAULT_MAX_BUCKETS = 10_000;
    private static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(15);

    private final Clock clock;
    private final int maxBuckets;
    private final Duration expireAfterAccess;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public InMemoryRateLimiter() {
        this(Clock.systemUTC());
    }

    public InMemoryRateLimiter(Clock clock) {
        this(clock, DEFAULT_MAX_BUCKETS, DEFAULT_EXPIRE_AFTER_ACCESS);
    }

    public InMemoryRateLimiter(Clock clock, int maxBuckets, Duration expireAfterAccess) {
        if (clock == null || maxBuckets <= 0 || expireAfterAccess == null || expireAfterAccess.isNegative()
                || expireAfterAccess.isZero()) {
            throw new IllegalArgumentException("Invalid rate limiter configuration");
        }
        this.clock = clock;
        this.maxBuckets = maxBuckets;
        this.expireAfterAccess = expireAfterAccess;
    }

    public synchronized boolean tryAcquire(RateLimitPolicy policy, String principal) {
        return tryAcquireDecision(policy, principal).allowed();
    }

    public synchronized RateLimitDecision tryAcquireDecision(RateLimitPolicy policy, String principal) {
        if (policy == null || principal == null || principal.isBlank()) {
            return new RateLimitDecision(false, 1L);
        }
        Instant now = clock.instant();
        cleanupExpired(now);
        String key = policy.name() + ':' + principal;
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxBuckets) {
                return new RateLimitDecision(false, 1L);
            }
            bucket = new Bucket(policy.capacity(), now);
            buckets.put(key, bucket);
        } else {
            bucket.refill(policy, now);
        }
        bucket.lastAccess = now;
        if (bucket.tokens < 1.0d) {
            return new RateLimitDecision(false, retryAfterSeconds(policy, principal));
        }
        bucket.tokens -= 1.0d;
        return new RateLimitDecision(true, 0L);
    }

    public synchronized int bucketCount() {
        cleanupExpired(clock.instant());
        return buckets.size();
    }

    public synchronized long retryAfterSeconds(RateLimitPolicy policy, String principal) {
        if (policy == null || principal == null || principal.isBlank()) {
            return 1L;
        }
        Instant now = clock.instant();
        cleanupExpired(now);
        Bucket bucket = buckets.get(policy.name() + ':' + principal);
        if (bucket == null) {
            return 0L;
        }
        bucket.refill(policy, now);
        bucket.lastAccess = now;
        if (bucket.tokens >= 1.0d) {
            return 0L;
        }
        double permitsPerSecond = (double) policy.capacity() / policy.window().toNanos() * 1_000_000_000d;
        return Math.max(1L, (long) Math.ceil((1.0d - bucket.tokens) / permitsPerSecond));
    }

    public synchronized void cleanupExpired() {
        cleanupExpired(clock.instant());
    }

    private void cleanupExpired(Instant now) {
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Bucket bucket = iterator.next().getValue();
            if (Duration.between(bucket.lastAccess, now).compareTo(expireAfterAccess) >= 0) {
                iterator.remove();
            }
        }
    }

    private static final class Bucket {
        private double tokens;
        private Instant lastRefill;
        private Instant lastAccess;

        private Bucket(int capacity, Instant now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastAccess = now;
        }

        private void refill(RateLimitPolicy policy, Instant now) {
            long elapsedNanos = Duration.between(lastRefill, now).toNanos();
            if (elapsedNanos > 0) {
                double refill = ((double) elapsedNanos / policy.window().toNanos()) * policy.capacity();
                tokens = Math.min(policy.capacity(), tokens + refill);
                lastRefill = now;
            }
        }
    }
}
