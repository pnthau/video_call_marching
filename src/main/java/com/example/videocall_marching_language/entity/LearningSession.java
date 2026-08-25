package com.example.videocall_marching_language.entity;

import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "learning_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_name", nullable = false, unique = true, length = 100)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "level_snapshot", nullable = false, length = 2)
    private JapaneseLevel levelSnapshot;

    @Column(name = "tag_snapshot", nullable = false, length = 100)
    private String tagSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.MATCHED;

    @Column(name = "matched_at", nullable = false)
    private LocalDateTime matchedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "user1_joined_agora_at")
    private LocalDateTime user1JoinedAgoraAt;

    @Column(name = "user1_left_agora_at")
    private LocalDateTime user1LeftAgoraAt;

    @Column(name = "user2_joined_agora_at")
    private LocalDateTime user2JoinedAgoraAt;

    @Column(name = "user2_left_agora_at")
    private LocalDateTime user2LeftAgoraAt;

    @Column(name = "overlapping_duration_seconds")
    private Integer overlappingDurationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_reason", length = 30)
    private CompletionReason completionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user1_id", nullable = false)
    private User user1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user2_id", nullable = false)
    private User user2;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}