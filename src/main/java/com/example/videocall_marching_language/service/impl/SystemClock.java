package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.service.TimeProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

@Component
public class SystemClock implements TimeProvider {

    private final java.time.Clock systemClock = java.time.Clock.systemUTC();

    @Override
    public Instant instant() {
        return systemClock.instant();
    }

    @Override
    public ZoneId getZone() {
        return systemClock.getZone();
    }

    @Override
    public TimeProvider withZone(ZoneId zone) {
        return new SystemClockWrapper(systemClock.withZone(zone));
    }

    private static class SystemClockWrapper implements TimeProvider {
        private final java.time.Clock delegate;

        SystemClockWrapper(java.time.Clock delegate) {
            this.delegate = delegate;
        }

        @Override
        public Instant instant() {
            return delegate.instant();
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public TimeProvider withZone(ZoneId zone) {
            return new SystemClockWrapper(delegate.withZone(zone));
        }
    }
}