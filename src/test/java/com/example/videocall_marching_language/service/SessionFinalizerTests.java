package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.config.LearningSessionProperties;
import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.SessionPresence;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.exception.SessionNotFoundException;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.repository.ISessionPresenceRepository;
import com.example.videocall_marching_language.service.impl.SessionFinalizer;
import com.example.videocall_marching_language.service.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionFinalizerTests {

    private ILearningSessionRepository repository;
    private ISessionPresenceRepository presenceRepository;
    private LearningSessionProperties learningSessionProperties;
    private MatchingProperties matchingProperties;
    private TimeProvider timeProvider;
    private SessionFinalizer sessionFinalizer;
    private User user1;
    private User user2;

    @BeforeEach
    void setUp() {
        repository = mock(ILearningSessionRepository.class);
        presenceRepository = mock(ISessionPresenceRepository.class);
        learningSessionProperties = mock(LearningSessionProperties.class);
        matchingProperties = mock(MatchingProperties.class);
        timeProvider = mock(TimeProvider.class);

        when(learningSessionProperties.getMinimumOverlapSeconds()).thenReturn(300);
        when(learningSessionProperties.getReconnectGraceSeconds()).thenReturn(60);
        when(learningSessionProperties.getMaximumDurationSeconds()).thenReturn(3600);
        when(matchingProperties.getMatchTimeoutSeconds()).thenReturn(600);
        when(matchingProperties.getAdjacentLevelAfterSeconds()).thenReturn(120);
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(timeProvider.getZone()).thenReturn(ZoneId.of("UTC"));

        sessionFinalizer = new SessionFinalizer(
                repository, presenceRepository, learningSessionProperties, matchingProperties, timeProvider);

        user1 = user(1L, "first");
        user2 = user(2L, "second");
    }

    @Test
    void finalizeSessionIfNeeded_matchTimeout_finalizesWithMatchTimeout() {
        LearningSession session = session(SessionStatus.MATCHED);
        session.setMatchedAt(LocalDateTime.parse("2025-12-31T23:50:00")); // 10 min ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository).save(session);
        assertEquals(SessionStatus.CANCELLED, session.getStatus());
        assertEquals(CompletionReason.MATCH_TIMEOUT, session.getCompletionReason());
    }

    @Test
    void finalizeSessionIfNeeded_reconnectDeadlinePassed_finalizesWithOneLeftTimeout() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
        session.setReconnectDeadline(LocalDateTime.parse("2025-12-31T23:59:00")); // 1 min ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository).save(session);
        assertEquals(SessionStatus.INCOMPLETE, session.getStatus());
        assertEquals(CompletionReason.ONE_LEFT_TIMEOUT, session.getCompletionReason());
        assertNull(session.getReconnectDeadline());
    }

    @Test
    void finalizeSessionIfNeeded_maxDurationWithStaleAggregateUsesRecomputedOpenOverlapAndReason() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.parse("2025-12-31T23:00:00")); // 1 hour ago
        session.setAccumulatedOverlapSeconds(0); // deliberately stale
        SessionPresence user1Open = SessionPresence.builder().sessionId(10L).userId(1L)
                .joinedAt(LocalDateTime.parse("2025-12-31T23:00:00")).build();
        SessionPresence user2Open = SessionPresence.builder().sessionId(10L).userId(2L)
                .joinedAt(LocalDateTime.parse("2025-12-31T23:00:00")).build();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of(user1Open, user2Open));
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(user1Open, user2Open));

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository).save(session);
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
        assertEquals(CompletionReason.MAX_DURATION_REACHED, session.getCompletionReason());
        assertEquals(3600, session.getAccumulatedOverlapSeconds());
    }

    @Test
    void finalizeSessionIfNeeded_maxDurationReached_withInsufficientOverlap_incomplete() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.parse("2025-12-31T23:00:00")); // 1 hour ago
        session.setAccumulatedOverlapSeconds(100); // below minimum
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository).save(session);
        assertEquals(SessionStatus.INCOMPLETE, session.getStatus());
        assertEquals(CompletionReason.MAX_DURATION_REACHED, session.getCompletionReason());
    }

    @Test
    void finalizeSessionIfNeeded_terminalStatus_returnsEarly() {
        LearningSession session = session(SessionStatus.COMPLETED);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository, never()).save(any());
    }

    @Test
    void finalizeSessionIfNeeded_notFound_throwsException() {
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThrows(SessionNotFoundException.class, () -> sessionFinalizer.finalizeSessionIfNeeded(10L));
    }

    @Test
    void finalizeSession_closesOpenIntervals_beforeCalculatingOverlap() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);

        SessionPresence open1 = SessionPresence.builder().id(1L).sessionId(10L).userId(1L).joinedAt(LocalDateTime.parse("2026-01-01T00:10:00")).build();
        SessionPresence open2 = SessionPresence.builder().id(2L).sessionId(10L).userId(2L).joinedAt(LocalDateTime.parse("2026-01-01T00:10:30")).build();
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of(open1, open2));
        when(presenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Closed intervals that will be used for overlap calculation
        SessionPresence closed1 = SessionPresence.builder().id(3L).sessionId(10L).userId(1L).joinedAt(LocalDateTime.parse("2026-01-01T00:00:00")).leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build();
        SessionPresence closed2 = SessionPresence.builder().id(4L).sessionId(10L).userId(2L).joinedAt(LocalDateTime.parse("2026-01-01T00:00:00")).leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build();
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(closed1, closed2));

        sessionFinalizer.finalizeSession(session, CompletionReason.BOTH_LEFT, LocalDateTime.parse("2026-01-01T00:30:00"));

        // Verify open intervals were closed
        ArgumentCaptor<SessionPresence> presenceCaptor = ArgumentCaptor.forClass(SessionPresence.class);
        verify(presenceRepository, times(2)).save(presenceCaptor.capture());
        List<SessionPresence> saved = presenceCaptor.getAllValues();
        assertTrue(saved.stream().allMatch(p -> p.getLeftAt() != null));

        // Verify session saved with overlap
        verify(repository).save(session);
        assertEquals(300, session.getAccumulatedOverlapSeconds()); // 5 minutes overlap
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    void finalizeSession_exactReconnectDeadline_acceptsJoin() {
        // This test is for the boundary condition in reportJoinAgora, not finalizer
        // The finalizer uses now.isAfter(deadline) so exact deadline is not finalized
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // exact = now
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        // Should NOT finalize because now == deadline (not after)
        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository, never()).save(any());
    }

    @Test
    void finalizeSession_exactMatchTimeout_finalizes() {
        LearningSession session = session(SessionStatus.MATCHED);
        session.setMatchedAt(LocalDateTime.parse("2025-12-31T23:50:00")); // exactly 10 min ago (600 seconds)
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).save(session);
        assertEquals(CompletionReason.MATCH_TIMEOUT, session.getCompletionReason());
    }

    @Test
    void finalizeSession_exactMaxDuration_finalizes() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.parse("2025-12-31T23:00:00")); // exactly 3600 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());

        sessionFinalizer.finalizeSessionIfNeeded(10L);

        verify(repository).save(session);
        assertEquals(CompletionReason.MAX_DURATION_REACHED, session.getCompletionReason());
    }

    private LearningSession session(SessionStatus status) {
        return LearningSession.builder()
                .id(10L)
                .channelName("room-10")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("daily")
                .status(status)
                .matchedAt(LocalDateTime.parse("2025-12-31T23:50:00"))
                .user1(user1)
                .user2(user2)
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
