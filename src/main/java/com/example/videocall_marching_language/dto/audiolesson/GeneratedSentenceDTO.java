package com.example.videocall_marching_language.dto.audiolesson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneratedSentenceDTO {
    private int sentenceIndex;
    private double startTime;
    private double endTime;
    private String originalText;
    private String phonetic;
    private String vietnameseMeaning;

    // Grammar
    private String grammarPoint;
    private String explanation;
    private String formula;
    private String keyWordsJson;
    private String sentenceChallengePrompt;
    private String challengeTargetSentence;

    // Exercise
    private String exerciseType;
    private String question;
    private List<String> options;
    private String correctAnswer;
    private String exerciseExplanation;
}
