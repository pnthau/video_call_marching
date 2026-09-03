package com.example.videocall_marching_language.service;

import java.time.Instant;
import java.time.ZoneId;

public interface TimeProvider {
    Instant instant();
    ZoneId getZone();
    TimeProvider withZone(ZoneId zone);
}