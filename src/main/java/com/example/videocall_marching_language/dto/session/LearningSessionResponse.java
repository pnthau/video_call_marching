package com.example.videocall_marching_language.dto.session;

import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningSessionResponse {
    private Long id;
    private String channelName;
    private JapaneseLevel levelSnapshot;
    private String tagSnapshot;
    private SessionStatus status;
    private LocalDateTime matchedAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer overlappingDurationSeconds;
    private Integer accumulatedOverlapSeconds;
    private CompletionReason completionReason;
    private Long user1Id;
    private String user1Username;
    private Long user2Id;
    private String user2Username;
    private Long currentUserId;
}