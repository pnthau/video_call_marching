package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonSentenceDTO {
    private Long id;
    private Integer sentenceIndex;
    private Double startTime;
    private Double endTime;
    private String originalText;
    private String phonetic;
    private String vietnameseMeaning;
    private SentenceGrammarDTO grammar;
    private List<SentenceExerciseDTO> exercises;
}
