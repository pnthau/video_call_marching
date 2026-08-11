package com.example.videocall_marching_language.dto.user;

import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.UserRole;

public record UserProfileResponse(
        Long id,
        String username,
        String phoneNumberMasked,
        JapaneseLevel currentLevel,
        float trustScore,
        String avatarUrl,
        boolean phoneVerified,
        UserRole role
) {
}
