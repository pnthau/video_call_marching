package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrammarChallengeRequestDTO {
    private Long sentenceId;
    private String userSentence;
    private String grammarFormula;
    private String grammarPoint;
    private String challengePrompt;
    private String language;
}
