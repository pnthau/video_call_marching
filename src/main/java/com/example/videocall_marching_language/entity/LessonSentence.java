package com.example.videocall_marching_language.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lesson_sentences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonSentence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    private AudioLesson audioLesson;

    @Column(name = "sentence_index", nullable = false)
    private Integer sentenceIndex;

    @Column(name = "start_time", nullable = false)
    private Double startTime;

    @Column(name = "end_time", nullable = false)
    private Double endTime;

    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT")
    private String originalText;

    @Column(columnDefinition = "TEXT")
    private String phonetic;

    @Column(name = "vietnamese_meaning", nullable = false, columnDefinition = "TEXT")
    private String vietnameseMeaning;

    @OneToOne(mappedBy = "lessonSentence", cascade = CascadeType.ALL, orphanRemoval = true)
    private SentenceGrammar grammar;

    @OneToMany(mappedBy = "lessonSentence", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SentenceExercise> exercises = new ArrayList<>();
}
