package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.config.LearningSessionProperties;
import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.SessionPresence;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.exception.SessionAccessDeniedException;
import com.example.videocall_marching_language.exception.SessionConflictException;
import com.example.videocall_marching_language.exception.SessionNotFoundException;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.repository.ISessionPresenceRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.AgoraTokenService;
import com.example.videocall_marching_language.service.AgoraUidPairGenerator;
import com.example.videocall_marching_language.service.TimeProvider;
import com.example.videocall_marching_language.service.impl.LearningSessionServiceImpl;
import com.example.videocall_marching_language.service.impl.SessionFinalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BoundaryConditionTests {

    private ILearningSessionRepository repository;
    private ISessionPresenceRepository presenceRepository;
    private AgoraTokenService agoraTokenService;
    private LearningSessionProperties learningSessionProperties;
    private MatchingProperties matchingProperties;
    private TimeProvider timeProvider;
    private LearningSessionServiceImpl service;
    private SessionFinalizer sessionFinalizer;
    private IUserRepository userRepository;
    private User user1;
    private User user2;

    @BeforeEach
    void setUp() {
        repository = mock(ILearningSessionRepository.class);
        presenceRepository = mock(ISessionPresenceRepository.class);
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
        when(timeProvider.getZone()).thenReturn(ZoneId.of("UTC"));

        sessionFinalizer = new SessionFinalizer(
                repository, presenceRepository, learningSessionProperties, matchingProperties, timeProvider);

        service = new LearningSessionServiceImpl(
                repository, presenceRepository, agoraTokenService,
                learningSessionProperties, matchingProperties, timeProvider, sessionFinalizer,
                userRepository, new AgoraUidPairGenerator());

        user1 = user(1L, "first");
        user2 = user(2L, "second");
    }

    @Test
    void reportJoinAgora_exactReconnectDeadline_acceptsJoin() {
        // now == reconnectDeadline should be accepted (not >)
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:01:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:01:00Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // exact match
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalForUpdate(10L, 1L)).thenReturn(Optional.empty());
        when(presenceRepository.findOpenIntervalForUpdate(10L, 2L)).thenReturn(Optional.empty());
        when(presenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LearningSession result = service.reportJoinAgora(10L, 1L);

        assertNotNull(result);
        assertEquals(SessionStatus.IN_PROGRESS, session.getStatus());
        verify(repository, atLeastOnce()).save(session);
    }

    @Test
    void reportJoinAgora_afterReconnectDeadline_finalizesAndThrows409() {
        // now > reconnectDeadline should finalize and throw
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:01:01");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:01:01Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // 1 second ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        SessionConflictException ex = assertThrows(SessionConflictException.class,
                () -> service.reportJoinAgora(10L, 1L));

        assertEquals(SessionConflictException.ConflictType.RECONNECT_DEADLINE_PASSED, ex.getConflictType());
        assertEquals(SessionStatus.INCOMPLETE, session.getStatus());
        assertEquals(CompletionReason.ONE_LEFT_TIMEOUT, session.getCompletionReason());
    }

    @Test
    void generateToken_exactReconnectDeadline_accepts() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:01:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:01:00Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // exact
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(agoraTokenService.generateToken(anyString(), anyInt())).thenReturn("token");

        assertDoesNotThrow(() -> service.generateTokenForSession(10L, 1L));
    }

    @Test
    void generateToken_afterReconnectDeadline_finalizesAndThrows409() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:01:01");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:01:01Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // 1 sec ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        SessionConflictException ex = assertThrows(SessionConflictException.class,
                () -> service.generateTokenForSession(10L, 1L));

        assertEquals(SessionConflictException.ConflictType.RECONNECT_DEADLINE_PASSED, ex.getConflictType());
    }

    @Test
    void generateToken_exactMatchTimeout_finalizesAndThrows409() {
        // now == matchTimeout should finalize (uses isAfter OR isEqual)
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:10:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:10:00Z"));

        LearningSession session = matchedSession();
        session.setMatchedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // exactly 600 seconds ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        SessionConflictException ex = assertThrows(SessionConflictException.class,
                () -> service.generateTokenForSession(10L, 1L));

        assertEquals(SessionConflictException.ConflictType.MATCH_TIMEOUT, ex.getConflictType());
        assertEquals(SessionStatus.CANCELLED, session.getStatus());
    }

    @Test
    void generateToken_exactMaxDuration_finalizesAndThrows409() {
        // now == maxDuration should finalize (uses isAfter OR isEqual)
        LocalDateTime now = LocalDateTime.parse("2026-01-01T01:00:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T01:00:00Z"));

        LearningSession session = inProgressSession();
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // exactly 3600 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(
                SessionPresence.builder().sessionId(10L).userId(1L)
                        .joinedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                        .leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build(),
                SessionPresence.builder().sessionId(10L).userId(2L)
                        .joinedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                        .leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build()));

        SessionConflictException ex = assertThrows(SessionConflictException.class,
                () -> service.generateTokenForSession(10L, 1L));

        assertEquals(SessionConflictException.ConflictType.MAX_DURATION_REACHED, ex.getConflictType());
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    void finalizeIfNeeded_exactReconnectDeadline_doesNotFinalize() {
        // Exact deadline remains valid; scheduler finalizes only strictly after it.
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:01:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:01:00Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // exact
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        service.finalizeIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository, never()).save(any());
        assertNull(session.getCompletionReason());
    }

    @Test
    void finalizeIfNeeded_beforeReconnectDeadline_doesNotFinalize() {
        // Scheduler uses now.isAfter() OR now.isEqual(), so before deadline not finalized
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:00:59Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // 1 sec in future
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        service.finalizeIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository, never()).save(any());
    }

    @Test
    void reportJoinAgora_exactMatchTimeout_finalizesAndThrows409() {
        // now == matchTimeout should finalize (uses isAfter OR isEqual)
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:10:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:10:00Z"));

        LearningSession session = matchedSession();
        session.setMatchedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // exactly 600 seconds ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        SessionConflictException ex = assertThrows(SessionConflictException.class,
                () -> service.reportJoinAgora(10L, 1L));

        assertEquals(SessionConflictException.ConflictType.MATCH_TIMEOUT, ex.getConflictType());
        assertEquals(SessionStatus.CANCELLED, session.getStatus());
    }

    @Test
    void reportJoinAgora_beforeMatchTimeout_accepts() {
        // now < matchTimeout should accept
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:09:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:09:59Z"));

        LearningSession session = matchedSession();
        session.setMatchedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // 599 seconds ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalForUpdate(10L, 1L)).thenReturn(Optional.empty());
        when(presenceRepository.findOpenIntervalForUpdate(10L, 2L)).thenReturn(Optional.empty());
        when(presenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        LearningSession result = service.reportJoinAgora(10L, 1L);

        assertNotNull(result);
        assertEquals(SessionStatus.MATCHED, session.getStatus());
        verify(repository, atLeastOnce()).save(session);
    }

    @Test
    void reportJoinAgora_exactMaxDuration_finalizesAndThrows409() {
        // now == maxDuration should finalize (uses isAfter OR isEqual)
        LocalDateTime now = LocalDateTime.parse("2026-01-01T01:00:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T01:00:00Z"));

        LearningSession session = inProgressSession();
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // exactly 3600 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(
                SessionPresence.builder().sessionId(10L).userId(1L)
                        .joinedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                        .leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build(),
                SessionPresence.builder().sessionId(10L).userId(2L)
                        .joinedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                        .leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build()));

        SessionConflictException ex = assertThrows(SessionConflictException.class,
                () -> service.reportJoinAgora(10L, 1L));

        assertEquals(SessionConflictException.ConflictType.MAX_DURATION_REACHED, ex.getConflictType());
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    void reportJoinAgora_beforeMaxDuration_accepts() {
        // now < maxDuration should accept
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:59:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:59:59Z"));

        LearningSession session = inProgressSession();
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // 3599 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalForUpdate(10L, 1L)).thenReturn(Optional.empty());
        when(presenceRepository.findOpenIntervalForUpdate(10L, 2L)).thenReturn(Optional.empty());
        when(presenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(
                SessionPresence.builder().sessionId(10L).userId(1L)
                        .joinedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                        .leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build(),
                SessionPresence.builder().sessionId(10L).userId(2L)
                        .joinedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                        .leftAt(LocalDateTime.parse("2026-01-01T00:05:00")).build()));

        LearningSession result = service.reportJoinAgora(10L, 1L);

        assertNotNull(result);
        assertEquals(SessionStatus.IN_PROGRESS, session.getStatus());
        verify(repository, atLeastOnce()).save(session);
    }

    @Test
    void generateToken_beforeReconnectDeadline_accepts() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:00:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:00:59Z"));

        LearningSession session = inProgressSession();
        session.setReconnectDeadline(LocalDateTime.parse("2026-01-01T00:01:00")); // 1 sec in future
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(agoraTokenService.generateToken(anyString(), anyInt())).thenReturn("token");

        assertDoesNotThrow(() -> service.generateTokenForSession(10L, 1L));
    }

    @Test
    void generateToken_beforeMatchTimeout_accepts() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:09:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:09:59Z"));

        LearningSession session = matchedSession();
        session.setMatchedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // 599 seconds ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(agoraTokenService.generateToken(anyString(), anyInt())).thenReturn("token");

        assertDoesNotThrow(() -> service.generateTokenForSession(10L, 1L));
    }

    @Test
    void generateToken_beforeMaxDuration_accepts() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:59:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:59:59Z"));

        LearningSession session = inProgressSession();
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // 3599 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(agoraTokenService.generateToken(anyString(), anyInt())).thenReturn("token");

        assertDoesNotThrow(() -> service.generateTokenForSession(10L, 1L));
    }

    @Test
    void finalizeIfNeeded_beforeMatchTimeout_doesNotFinalize() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:09:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:09:59Z"));

        LearningSession session = matchedSession();
        session.setMatchedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // 599 seconds ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        service.finalizeIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository, never()).save(any());
    }

    @Test
    void finalizeIfNeeded_beforeMaxDuration_doesNotFinalize() {
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:59:59");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:59:59Z"));

        LearningSession session = inProgressSession();
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // 3599 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        service.finalizeIfNeeded(10L);

        verify(repository).findByIdForUpdate(10L);
        verify(repository, never()).save(any());
    }

    @Test
    void finalizeIfNeeded_exactMatchTimeout_finalizes() {
        // Scheduler uses now.isAfter() OR now.isEqual()
        LocalDateTime now = LocalDateTime.parse("2026-01-01T00:10:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:10:00Z"));

        LearningSession session = matchedSession();
        session.setMatchedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // exactly 600 seconds ago
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        service.finalizeIfNeeded(10L);

        verify(repository).save(session);
        assertEquals(CompletionReason.MATCH_TIMEOUT, session.getCompletionReason());
    }

    @Test
    void finalizeIfNeeded_exactMaxDuration_finalizes() {
        // Scheduler uses now.isAfter() OR now.isEqual()
        LocalDateTime now = LocalDateTime.parse("2026-01-01T01:00:00");
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T01:00:00Z"));

        LearningSession session = inProgressSession();
        session.setStartedAt(LocalDateTime.parse("2026-01-01T00:00:00")); // exactly 3600 seconds ago
        session.setAccumulatedOverlapSeconds(300);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalsBySessionId(10L)).thenReturn(List.of());
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of());

        service.finalizeIfNeeded(10L);

        verify(repository).save(session);
        assertEquals(CompletionReason.MAX_DURATION_REACHED, session.getCompletionReason());
    }

    private LearningSession matchedSession() {
        LearningSession session = LearningSession.builder()
                .id(10L)
                .channelName("room-10")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("daily")
                .status(SessionStatus.MATCHED)
                .matchedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                .user1(user1)
                .user2(user2)
                .user1Uid(1)
                .user2Uid(2)
                .accumulatedOverlapSeconds(0)
                .build();
        return session;
    }

    private LearningSession inProgressSession() {
        LearningSession session = LearningSession.builder()
                .id(10L)
                .channelName("room-10")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("daily")
                .status(SessionStatus.IN_PROGRESS)
                .matchedAt(LocalDateTime.parse("2026-01-01T00:00:00"))
                .startedAt(LocalDateTime.parse("2026-01-01T00:00:30"))
                .user1(user1)
                .user2(user2)
                .user1Uid(1)
                .user2Uid(2)
                .accumulatedOverlapSeconds(0)
                .build();
        return session;
    }

    private User user(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@example.com")
                .build();
    }
}
