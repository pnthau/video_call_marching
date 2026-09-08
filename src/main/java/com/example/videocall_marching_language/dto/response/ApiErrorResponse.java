package com.example.videocall_marching_language.dto.response;

import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        Instant timestamp,
        String path
) {
}
