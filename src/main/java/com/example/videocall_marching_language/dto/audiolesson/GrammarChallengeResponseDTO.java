package com.example.videocall_marching_language.dto.audiolesson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GrammarChallengeResponseDTO {
    private boolean isCorrect;
    private int score;
    private String feedback;
    private String correctedSentence;
    private String suggestedSentence;
}
