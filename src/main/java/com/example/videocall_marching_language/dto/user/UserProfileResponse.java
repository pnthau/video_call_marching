package com.example.videocall_marching_language.dto.user;

import com.example.videocall_marching_language.enums.AIProvider;
import com.example.videocall_marching_language.enums.UserRole;

import java.util.Collections;
import java.util.List;

public record UserProfileResponse(
        Long id,
        String username,
        String email,
        float trustScore,
        String avatarUrl,
        UserRole role,
        AIProvider provider,
        String apiKey,
        List<AIProvider> configuredProviders
) {
    public UserProfileResponse(
            Long id,
            String username,
            String email,
            float trustScore,
            String avatarUrl,
            UserRole role,
            AIProvider provider,
            String apiKey
    ) {
        this(id, username, email, trustScore, avatarUrl, role, provider, apiKey, Collections.emptyList());
    }
}
