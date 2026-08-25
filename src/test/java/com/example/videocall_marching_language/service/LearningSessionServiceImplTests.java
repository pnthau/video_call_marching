package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.exception.SessionAccessDeniedException;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.service.impl.LearningSessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
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
    private AgoraTokenService agoraTokenService;
    private LearningSessionServiceImpl service;
    private User user1;
    private User user2;

    @BeforeEach
    void setUp() {
        repository = mock(ILearningSessionRepository.class);
        agoraTokenService = mock(AgoraTokenService.class);
        service = new LearningSessionServiceImpl(repository, agoraTokenService);
        user1 = user(1L, "first");
        user2 = user(2L, "second");
    }

    @Test
    void createSessionReturnsExistingActivePairRegardlessOfUserOrder() {
        LearningSession existing = session(SessionStatus.MATCHED);
        when(repository.findActiveSessionBetweenUsers(2L, 1L,
                List.of(SessionStatus.MATCHED, SessionStatus.IN_PROGRESS)))
                .thenReturn(Optional.of(existing));

        LearningSession result = service.createSession(
                user2, user1, JapaneseLevel.N5, "daily", "room-new");

        assertSame(existing, result);
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

        LearningSession result = service.reportLeaveAgora(10L, 1L);

        assertSame(session, result);
        assertEquals(SessionStatus.IN_PROGRESS, session.getStatus());
        assertNull(session.getEndedAt());
        assertNull(session.getCompletionReason());
        assertTrue(session.getUser1LeftAgoraAt() != null);
        verify(repository).findByIdForUpdate(10L);
    }

    @Test
    void secondLeaveEndsSessionNormallyAndCalculatesOverlap() {
        LearningSession session = inProgressSession();
        session.setUser1LeftAgoraAt(LocalDateTime.now().minusSeconds(30));
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));
        when(repository.save(session)).thenReturn(session);

        service.reportLeaveAgora(10L, 2L);

        assertEquals(SessionStatus.ENDED, session.getStatus());
        assertEquals(CompletionReason.NORMAL, session.getCompletionReason());
        assertTrue(session.getEndedAt() != null);
        assertTrue(session.getOverlappingDurationSeconds() >= 0);
    }

    @Test
    void concurrentLifecycleMutationsUseWriteLockedLookup() {
        LearningSession session = inProgressSession();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        service.endSession(10L, 1L, CompletionReason.PEER_LEFT);

        verify(repository).findByIdForUpdate(10L);
        verify(repository).save(session);
        assertEquals(SessionStatus.ENDED, session.getStatus());
    }

    @Test
    void nonParticipantCannotMutateSession() {
        LearningSession session = inProgressSession();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(session));

        assertThrows(SessionAccessDeniedException.class,
                () -> service.reportJoinAgora(10L, 99L));

        verify(repository, never()).save(any());
    }

    private LearningSession inProgressSession() {
        LearningSession session = session(SessionStatus.IN_PROGRESS);
        session.setUser1JoinedAgoraAt(LocalDateTime.now().minusMinutes(2));
        session.setUser2JoinedAgoraAt(LocalDateTime.now().minusMinutes(1));
        session.setStartedAt(LocalDateTime.now().minusMinutes(1));
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
