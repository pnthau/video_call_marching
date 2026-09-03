package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.config.LearningSessionProperties;
import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.service.impl.LearningSessionServiceImpl;
import com.example.videocall_marching_language.service.impl.SessionFinalizer;
import com.example.videocall_marching_language.service.AgoraTokenService;
import com.example.videocall_marching_language.service.AgoraUidPairGenerator;
import com.example.videocall_marching_language.service.TimeProvider;
import com.example.videocall_marching_language.repository.ISessionPresenceRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.VideocallMarchingLanguageApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionSchedulerTests {

    @Test
    void applicationEnablesScheduling() {
        assertTrue(VideocallMarchingLanguageApplication.class.isAnnotationPresent(EnableScheduling.class));
    }

    @Test
    void schedulerNotifiesBothParticipantsAfterCommittedFinalization() {
        ILearningSessionService service = mock(ILearningSessionService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        SessionScheduler scheduler = new SessionScheduler(service, messaging);
        LearningSession active = session(SessionStatus.IN_PROGRESS, 10L);
        LearningSession terminal = session(SessionStatus.INCOMPLETE, 10L);
        when(service.findAllActiveSessions()).thenReturn(List.of(active));
        when(service.findById(10L)).thenReturn(Optional.of(terminal));

        scheduler.finalizeExpiredSessions();

        verify(service).finalizeAllActiveSessions();
        verify(messaging).convertAndSend(eq("/topic/match/1"), any(Object.class));
        verify(messaging).convertAndSend(eq("/topic/match/2"), any(Object.class));
    }

    @Test
    void notificationFailureDoesNotUndoFinalizationBatch() {
        ILearningSessionService service = mock(ILearningSessionService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        SessionScheduler scheduler = new SessionScheduler(service, messaging);
        LearningSession active = session(SessionStatus.IN_PROGRESS, 10L);
        LearningSession terminal = session(SessionStatus.INCOMPLETE, 10L);
        when(service.findAllActiveSessions()).thenReturn(List.of(active));
        when(service.findById(10L)).thenReturn(Optional.of(terminal));
        doThrow(new RuntimeException("broker unavailable"))
                .when(messaging).convertAndSend(eq("/topic/match/1"), any(Object.class));

        assertDoesNotThrow(scheduler::finalizeExpiredSessions);
        verify(service).finalizeAllActiveSessions();
    }

    private ILearningSessionRepository learningSessionRepository;
    private ISessionPresenceRepository sessionPresenceRepository;
    private AgoraTokenService agoraTokenService;
    private LearningSessionProperties learningSessionProperties;
    private MatchingProperties matchingProperties;
    private TimeProvider timeProvider;
    private LearningSessionServiceImpl learningSessionService;
    private SessionFinalizer sessionFinalizer;
    private IUserRepository userRepository;

    @BeforeEach
    void setUp() {
        learningSessionRepository = mock(ILearningSessionRepository.class);
        sessionPresenceRepository = mock(ISessionPresenceRepository.class);
        agoraTokenService = mock(AgoraTokenService.class);
        learningSessionProperties = mock(LearningSessionProperties.class);
        matchingProperties = mock(MatchingProperties.class);
        timeProvider = mock(TimeProvider.class);
        userRepository = mock(IUserRepository.class);

        when(learningSessionProperties.getMinimumOverlapSeconds()).thenReturn(300);
        when(learningSessionProperties.getReconnectGraceSeconds()).thenReturn(60);
        when(learningSessionProperties.getMaximumDurationSeconds()).thenReturn(3600);
        when(matchingProperties.getMatchTimeoutSeconds()).thenReturn(600);
        when(matchingProperties.getAdjacentLevelAfterSeconds()).thenReturn(120);
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(timeProvider.getZone()).thenReturn(ZoneId.of("UTC"));

        sessionFinalizer = mock(SessionFinalizer.class);

        learningSessionService = new LearningSessionServiceImpl(
                learningSessionRepository, sessionPresenceRepository, agoraTokenService,
                learningSessionProperties, matchingProperties, timeProvider, sessionFinalizer,
                userRepository, new AgoraUidPairGenerator());
    }

    @Test
    void finalizeAllActiveSessions_processesReconnectDeadlineSessions_independently() {
        // 600 seconds (match timeout)
        LearningSession session1 = session(SessionStatus.IN_PROGRESS, 1L);
        session1.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00")); // past deadline

        // 60 seconds (reconnect grace)
        LearningSession session2 = session(SessionStatus.IN_PROGRESS, 2L);
        session2.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00")); // past deadline

        // 3600 seconds (max duration)
        LearningSession session3 = session(SessionStatus.IN_PROGRESS, 3L);
        session3.setStartedAt(LocalDateTime.parse("2025-12-31T23:00:00")); // 1 hour ago
        session3.setAccumulatedOverlapSeconds(100);

        when(learningSessionRepository.findSessionsPastReconnectDeadline(any(), any()))
                .thenReturn(List.of(session1, session2));
        when(learningSessionRepository.findSessionsPastMaxDuration(any(), any()))
                .thenReturn(List.of(session3));
        when(learningSessionRepository.findMatchedSessionsPastTimeout(any(), any()))
                .thenReturn(List.of());

        // Mock finalizer to not actually finalize (we're testing scheduler calls)
        doNothing().when(sessionFinalizer).finalizeSessionIfNeeded(anyLong());

        learningSessionService.finalizeAllActiveSessions();

        // Verify finalizer called for each session independently
        verify(sessionFinalizer, times(3)).finalizeSessionIfNeeded(anyLong());
    }

    @Test
    void finalizeAllActiveSessions_oneFailureDoesNotRollbackOthers() {
        LearningSession session1 = session(SessionStatus.IN_PROGRESS, 1L);
        session1.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00"));

        LearningSession session2 = session(SessionStatus.IN_PROGRESS, 2L);
        session2.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00"));

        when(learningSessionRepository.findSessionsPastReconnectDeadline(any(), any()))
                .thenReturn(List.of(session1, session2));
        when(learningSessionRepository.findSessionsPastMaxDuration(any(), any()))
                .thenReturn(List.of());
        when(learningSessionRepository.findMatchedSessionsPastTimeout(any(), any()))
                .thenReturn(List.of());

        // First call succeeds, second throws
        doNothing().when(sessionFinalizer).finalizeSessionIfNeeded(1L);
        doThrow(new RuntimeException("DB error")).when(sessionFinalizer).finalizeSessionIfNeeded(2L);

        // Should not throw - failures are caught and logged
        learningSessionService.finalizeAllActiveSessions();

        // Both should have been attempted
        verify(sessionFinalizer).finalizeSessionIfNeeded(1L);
        verify(sessionFinalizer).finalizeSessionIfNeeded(2L);
    }

    @Test
    void finalizeAllActiveSessions_matchTimeout_processed() {
        LearningSession session = session(SessionStatus.MATCHED, 1L);
        session.setMatchedAt(LocalDateTime.parse("2025-12-31T23:50:00"));

        when(learningSessionRepository.findSessionsPastReconnectDeadline(any(), any()))
                .thenReturn(List.of());
        when(learningSessionRepository.findSessionsPastMaxDuration(any(), any()))
                .thenReturn(List.of());
        when(learningSessionRepository.findMatchedSessionsPastTimeout(any(), any()))
                .thenReturn(List.of(session));

        doNothing().when(sessionFinalizer).finalizeSessionIfNeeded(anyLong());

        learningSessionService.finalizeAllActiveSessions();

        verify(sessionFinalizer).finalizeSessionIfNeeded(1L);
    }

    @Test
    void finalizeAllActiveSessions_restartRecovery_exactlyOnce() {
        // Simulate scheduler running twice (e.g., app restart)
        LearningSession session = session(SessionStatus.IN_PROGRESS, 1L);
        session.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00"));

        when(learningSessionRepository.findSessionsPastReconnectDeadline(any(), any()))
                .thenReturn(List.of(session));
        when(learningSessionRepository.findSessionsPastMaxDuration(any(), any()))
                .thenReturn(List.of());
        when(learningSessionRepository.findMatchedSessionsPastTimeout(any(), any()))
                .thenReturn(List.of());

        doNothing().when(sessionFinalizer).finalizeSessionIfNeeded(anyLong());

        // First run
        learningSessionService.finalizeAllActiveSessions();
        // Second run (simulating restart)
        learningSessionService.finalizeAllActiveSessions();

        // Session should be processed twice but finalizer should handle terminal status
        verify(sessionFinalizer, times(2)).finalizeSessionIfNeeded(1L);
    }

    @Test
    void finalizeAllActiveSessions_exactlyOncePerSession() {
        // Verify each session is processed exactly once per scheduler run
        LearningSession session1 = session(SessionStatus.IN_PROGRESS, 1L);
        session1.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00"));

        LearningSession session2 = session(SessionStatus.IN_PROGRESS, 2L);
        session2.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00"));

        when(learningSessionRepository.findSessionsPastReconnectDeadline(any(), any()))
                .thenReturn(List.of(session1, session2));
        when(learningSessionRepository.findSessionsPastMaxDuration(any(), any()))
                .thenReturn(List.of());
        when(learningSessionRepository.findMatchedSessionsPastTimeout(any(), any()))
                .thenReturn(List.of());

        doNothing().when(sessionFinalizer).finalizeSessionIfNeeded(anyLong());

        learningSessionService.finalizeAllActiveSessions();

        verify(sessionFinalizer).finalizeSessionIfNeeded(1L);
        verify(sessionFinalizer).finalizeSessionIfNeeded(2L);
        verify(sessionFinalizer, times(2)).finalizeSessionIfNeeded(anyLong());
    }

    private LearningSession session(SessionStatus status, Long id) {
        return LearningSession.builder()
                .id(id)
                .channelName("room-" + id)
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("daily")
                .status(status)
                .matchedAt(LocalDateTime.parse("2025-12-31T23:50:00"))
                .user1(user(1L, "first"))
                .user2(user(2L, "second"))
                .user1Uid(1)
                .user2Uid(2)
                .accumulatedOverlapSeconds(0)
                .build();
    }

    private User user(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@example.com")
                .build();
    }
}
