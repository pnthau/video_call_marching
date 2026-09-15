package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhisperSegmentDTO {
    private int sentenceIndex;
    private double startTime;
    private double endTime;
    private String text;
}
