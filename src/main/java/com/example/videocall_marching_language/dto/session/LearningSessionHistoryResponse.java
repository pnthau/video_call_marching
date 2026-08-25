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
public class LearningSessionHistoryResponse {
    private Long id;
    private String channelName;
    private JapaneseLevel levelSnapshot;
    private String tagSnapshot;
    private SessionStatus status;
    private LocalDateTime matchedAt;
    private LocalDateTime endedAt;
    private Integer overlappingDurationSeconds;
    private CompletionReason completionReason;
    private Long peerId;
    private String peerUsername;
}