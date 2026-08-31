package com.example.videocall_marching_language.dto.admin;

import com.example.videocall_marching_language.enums.RubricCriteria;

public record RubricResponse(Long id, RubricCriteria criteria, String displayName,
                             String description, boolean active) {
}
