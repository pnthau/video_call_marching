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
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.repository.ISessionPresenceRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.impl.SessionFinalizer;
import com.example.videocall_marching_language.service.impl.LearningSessionServiceImpl;
import com.example.videocall_marching_language.service.AgoraTokenService;
import com.example.videocall_marching_language.service.AgoraUidPairGenerator;
import com.example.videocall_marching_language.service.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningSessionServiceImplTests {

    private ILearningSessionRepository repository;
    private ISessionPresenceRepository presenceRepository;
    private AgoraTokenService agoraTokenService;
    private LearningSessionProperties learningSessionProperties;
    private MatchingProperties matchingProperties;
    private TimeProvider timeProvider;
    private LearningSessionServiceImpl service;
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
        when(timeProvider.instant()).thenReturn(Instant.now());
        when(timeProvider.getZone()).thenReturn(ZoneId.systemDefault());

        SessionFinalizer sessionFinalizer = new SessionFinalizer(
                repository, presenceRepository, learningSessionProperties, matchingProperties, timeProvider);
        service = new LearningSessionServiceImpl(
                repository, presenceRepository, agoraTokenService,
                learningSessionProperties, matchingProperties, timeProvider, sessionFinalizer,
                userRepository, new AgoraUidPairGenerator());

        user1 = user(1L, "first");
        user2 = user(2L, "second");
        when(userRepository.findAllByIdForUpdate(any())).thenReturn(List.of(user1, user2));
    }

    @Test
    void createSessionReturnsExistingActivePairRegardlessOfUserOrder() {
        LearningSession existing = session(SessionStatus.MATCHED);
        when(repository.findActiveSessionBetweenUsers(2L, 1L,
                List.of(SessionStatus.MATCHED, SessionStatus.IN_PROGRESS)))
                .thenReturn(Optional.of(existing));
        when(repository.findActiveSessionByUserIdWithLock(any(), any())).thenReturn(Optional.of(existing));

        LearningSession result = service.createSession(
                user2, user1, JapaneseLevel.N5, "daily", "room-new");

        assertSame(existing, result);
        verify(repository, never()).findActiveSessionByUserIdWithLock(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void createSessionRejectsMatchingUserWithThemself() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createSession(user1, user1, JapaneseLevel.N5, "daily", "room-new"));

        verify(repository, never()).save(any());
    }

    @Test
    void firstLeaveDoesNotPrematurelyEndSession() {
        LearningSession session = inProgressSession();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);
        when(presenceRepository.findOpenIntervalForUpdate(10L, 1L)).thenReturn(Optional.empty());

        LearningSession result = service.reportLeaveAgora(10L, 1L);

        assertSame(session, result);
        assertEquals(SessionStatus.IN_PROGRESS, session.getStatus());
        assertNull(session.getEndedAt());
        assertNull(session.getCompletionReason());
        verify(repository).findByIdForUpdate(10L);
    }

    @Test
    void secondLeaveEndsSessionNormallyAndCalculatesOverlap() {
        LearningSession session = inProgressSession();
        session.setUser1LeftAgoraAt(LocalDateTime.now().minusSeconds(30));
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);

        SessionPresence user2Presence = SessionPresence.builder()
                .id(1L)
                .sessionId(10L)
                .userId(2L)
                .joinedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(presenceRepository.findOpenIntervalForUpdate(10L, 2L)).thenReturn(Optional.of(user2Presence));
        when(presenceRepository.findOpenIntervalForUpdate(10L, 1L)).thenReturn(Optional.empty());

        LocalDateTime now = LocalDateTime.now();
        SessionPresence closed1 = SessionPresence.builder()
                .id(2L)
                .sessionId(10L)
                .userId(1L)
                .joinedAt(now.minusMinutes(10))
                .leftAt(now.minusMinutes(5))
                .build();
        SessionPresence closed2 = SessionPresence.builder()
                .id(3L)
                .sessionId(10L)
                .userId(2L)
                .joinedAt(now.minusMinutes(10))
                .leftAt(now.minusMinutes(5))
                .build();
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(closed1, closed2));
        when(presenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reportLeaveAgora(10L, 2L);

        assertEquals(SessionStatus.COMPLETED, session.getStatus());
        assertEquals(CompletionReason.BOTH_LEFT, session.getCompletionReason());
        assertTrue(session.getEndedAt() != null);
    }

    @Test
    void concurrentLifecycleMutationsUseWriteLockedLookup() {
        LearningSession session = inProgressSession();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);

        SessionPresence user1Presence = SessionPresence.builder()
                .id(1L)
                .sessionId(10L)
                .userId(1L)
                .joinedAt(LocalDateTime.now().minusMinutes(2))
                .build();
        when(presenceRepository.findOpenIntervalForUpdate(10L, 1L)).thenReturn(Optional.of(user1Presence));
        when(presenceRepository.findOpenIntervalForUpdate(10L, 2L)).thenReturn(Optional.empty());

        LocalDateTime now = LocalDateTime.now();
        SessionPresence closed1 = SessionPresence.builder()
                .id(2L)
                .sessionId(10L)
                .userId(1L)
                .joinedAt(now.minusMinutes(10))
                .leftAt(now.minusMinutes(5))
                .build();
        SessionPresence closed2 = SessionPresence.builder()
                .id(3L)
                .sessionId(10L)
                .userId(2L)
                .joinedAt(now.minusMinutes(10))
                .leftAt(now.minusMinutes(5))
                .build();
        when(presenceRepository.findClosedIntervalsBySessionId(10L)).thenReturn(List.of(closed1, closed2));
        when(presenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.endSession(10L, 1L, CompletionReason.ONE_LEFT_TIMEOUT);

        verify(repository, org.mockito.Mockito.atLeastOnce()).findByIdForUpdate(10L);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(session);
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    void nonParticipantCannotMutateSession() {
        LearningSession session = inProgressSession();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(presenceRepository.findOpenIntervalForUpdate(10L, 99L)).thenReturn(Optional.empty());

        assertThrows(SessionAccessDeniedException.class,
                () -> service.reportJoinAgora(10L, 99L));

        verify(repository, never()).save(any());
    }

    private LearningSession inProgressSession() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setUser1JoinedAgoraAt(LocalDateTime.now().minusMinutes(2));
        session.setUser2JoinedAgoraAt(LocalDateTime.now().minusMinutes(1));
        session.setStartedAt(LocalDateTime.now().minusMinutes(1));
        session.setUser1Uid(1);
        session.setUser2Uid(2);
        return session;
    }

    private LearningSession session(SessionStatus status) {
        return LearningSession.builder()
                .id(10L)
                .channelName("room-10")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("daily")
                .status(status)
                .matchedAt(LocalDateTime.now().minusMinutes(3))
                .user1(user1)
                .user2(user2)
                .user1Uid(1)
                .user2Uid(2)
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
