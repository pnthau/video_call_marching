package com.example.videocall_marching_language.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TagCategoryType {
    TOPIC("Chủ đề bài học"),
    LEVEL("Trình độ luyện tập"),
    ACTIVITY("Hình thức học");

    private final String displayName;
}
