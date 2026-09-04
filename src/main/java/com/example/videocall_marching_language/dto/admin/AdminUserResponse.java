package com.example.videocall_marching_language.dto.admin;

import com.example.videocall_marching_language.enums.JapaneseLevel;

public record AdminUserResponse(Long id, String username, String email, JapaneseLevel currentLevel) {
}
