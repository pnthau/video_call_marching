package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExerciseCheckResponseDTO {
    private boolean isCorrect;
    private String correctAnswer;
    private String explanation;
}
