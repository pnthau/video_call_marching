package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AudioLessonDTO {
    private Long id;
    private Long tagId;
    private String tagName;
    private Long userId;
    private String title;
    private String audioUrl;
    private Integer durationInSeconds;
    private String language;
    private String level;
    private String status;
    private Long scriptId;
    private String createdAt;
    private List<LessonSentenceDTO> sentences;
}
