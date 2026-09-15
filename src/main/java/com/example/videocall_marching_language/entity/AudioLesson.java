package com.example.videocall_marching_language.entity;

import com.example.videocall_marching_language.enums.AudioLessonStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "audio_lessons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AudioLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id")
    private Script script;

    @Column(nullable = false)
    private String title;

    @Column(name = "audio_url", nullable = false, length = 500)
    private String audioUrl;

    @Column(name = "duration_in_seconds")
    private Integer durationInSeconds;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String language = "ja";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String level = "N5";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AudioLessonStatus status = AudioLessonStatus.READY;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "audioLesson", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LessonSentence> sentences = new ArrayList<>();
}
