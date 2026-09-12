package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentenceGrammarDTO {
    private Long id;
    private String grammarPoint;
    private String explanation;
    private String formula;
    private String keyWordsJson;
    private String sentenceChallengePrompt;
}
