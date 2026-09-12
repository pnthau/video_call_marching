package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentenceExerciseDTO {
    private Long id;
    private String exerciseType;
    private String question;
    private String optionsJson;
    private List<String> options;
    private String correctAnswer;
    private String explanation;
}
