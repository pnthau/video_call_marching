package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ILearningSessionService {

    LearningSession createSession(User user1, User user2, JapaneseLevel level, String tag, String channelName);

    Optional<LearningSession> findById(Long id);

    Optional<LearningSession> findByChannelName(String channelName);

    Optional<LearningSession> findByChannelNameAndParticipant(String channelName, Long userId);

    Page<LearningSession> getHistory(Long userId, Pageable pageable);

    LearningSession reportJoinAgora(Long sessionId, Long userId);

    LearningSession reportLeaveAgora(Long sessionId, Long userId);

    String generateTokenForSession(Long sessionId, Long userId);

    void endSession(Long sessionId, Long userId, CompletionReason reason);

    boolean isParticipant(Long sessionId, Long userId);
}