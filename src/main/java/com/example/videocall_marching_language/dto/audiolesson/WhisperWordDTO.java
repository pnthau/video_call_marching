package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WhisperWordDTO {
    private String word;
    private double startTime;
    private double endTime;
}
