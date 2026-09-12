package com.example.videocall_marching_language.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sentence_grammars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentenceGrammar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sentence_id", nullable = false)
    private LessonSentence lessonSentence;

    @Column(name = "grammar_point", nullable = false)
    private String grammarPoint;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(length = 255)
    private String formula;

    @Column(name = "key_words_json", columnDefinition = "TEXT")
    private String keyWordsJson;

    @Column(name = "sentence_challenge_prompt", nullable = false, columnDefinition = "TEXT")
    private String sentenceChallengePrompt;
}
