package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

import java.util.List;

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
    private Double noSpeechProb;
    private Double avgLogprob;
    private Double compressionRatio;
    private List<WhisperWordDTO> words;
}
