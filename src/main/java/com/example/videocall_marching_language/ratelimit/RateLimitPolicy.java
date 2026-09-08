package com.example.videocall_marching_language.ratelimit;

import java.time.Duration;

public enum RateLimitPolicy {
    SESSION_TOKEN(10, Duration.ofMinutes(1)),
    SESSION_ACTIVE(30, Duration.ofMinutes(1)),
    SESSION_JOIN(10, Duration.ofMinutes(1)),
    SESSION_LEAVE(10, Duration.ofMinutes(1)),
    AVATAR_UPLOAD(5, Duration.ofMinutes(10)),
    ADMIN_MUTATION(30, Duration.ofMinutes(1));

    private final int capacity;
    private final Duration window;

    RateLimitPolicy(int capacity, Duration window) {
        this.capacity = capacity;
        this.window = window;
    }

    public int capacity() { return capacity; }
    public Duration window() { return window; }
}
