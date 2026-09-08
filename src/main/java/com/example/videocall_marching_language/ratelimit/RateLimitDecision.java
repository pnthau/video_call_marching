package com.example.videocall_marching_language.ratelimit;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    public RateLimitDecision {
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative");
        }
    }
}
