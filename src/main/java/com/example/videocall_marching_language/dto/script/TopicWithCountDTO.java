package com.example.videocall_marching_language.dto.script;

import lombok.*;

/**
 * DTO chứa thông tin tổng hợp của từng Chủ đề (Tag)
 * theo từng Ngôn ngữ và Số lượng bài học tương ứng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicWithCountDTO {
    private Long tagId;
    private String tagName;
    private String language;
    private String level;
    private Long scriptCount;
}