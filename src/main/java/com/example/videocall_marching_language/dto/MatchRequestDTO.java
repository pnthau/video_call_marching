package com.example.videocall_marching_language.dto;

import com.example.videocall_marching_language.enums.JapaneseLevel;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MatchRequestDTO {
    private Long userId;
    private String tagKey;
    private Long topicTagId;
    private Long levelTagId;
    private Long activityTagId;
    private JapaneseLevel level;
}
