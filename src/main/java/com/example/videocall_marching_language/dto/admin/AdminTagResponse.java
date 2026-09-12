package com.example.videocall_marching_language.dto.admin;

import com.example.videocall_marching_language.enums.TagCategoryType;

public record AdminTagResponse(
        Long id,
        String name,
        Long categoryId,
        String categoryName,
        TagCategoryType categoryType
) {
}
