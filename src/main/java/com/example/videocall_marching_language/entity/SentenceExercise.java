package com.example.videocall_marching_language.entity;

import com.example.videocall_marching_language.enums.ExerciseType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sentence_exercises")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentenceExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sentence_id", nullable = false)
    private LessonSentence lessonSentence;

    @Enumerated(EnumType.STRING)
    @Column(name = "exercise_type", nullable = false, length = 30)
    private ExerciseType exerciseType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "options_json", nullable = false, columnDefinition = "TEXT")
    private String optionsJson;

    @Column(name = "correct_answer", nullable = false)
    private String correctAnswer;

    @Column(columnDefinition = "TEXT")
    private String explanation;
}
