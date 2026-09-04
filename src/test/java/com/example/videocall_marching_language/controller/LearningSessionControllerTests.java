package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.controller.user.LearningSessionController;
import com.example.videocall_marching_language.dto.session.LearningSessionHistoryResponse;
import com.example.videocall_marching_language.dto.session.SessionTokenDTO;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.service.ILearningSessionService;
import com.example.videocall_marching_language.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningSessionControllerTests {

    @Test
    void activeSessionIsResolvedFromAuthenticatedUser() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L).channelName("server-room").status(SessionStatus.IN_PROGRESS)
                .user1(user1).user2(user2).build();
        when(authentication.getName()).thenReturn(user1.getEmail());
        when(userService.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(sessionService.findActiveSessionByUserId(1L)).thenReturn(Optional.of(session));

        var response = controller.getActiveSession(authentication);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(100L, response.getBody().getId());
        assertEquals("server-room", response.getBody().getChannelName());
        verify(sessionService).findActiveSessionByUserId(1L);
    }

    @Test
    void noActiveSessionReturns204() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user = user(1L, "user1");
        when(authentication.getName()).thenReturn(user.getEmail());
        when(userService.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(sessionService.findActiveSessionByUserId(1L)).thenReturn(Optional.empty());

        assertEquals(204, controller.getActiveSession(authentication).getStatusCode().value());
    }

    @Test
    void historyReturnsTheOtherParticipantWhenCurrentUserIsUser2() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "first");
        User user2 = user(2L, "second");
        LearningSession session = LearningSession.builder()
                .id(10L)
                .channelName("room-10")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("daily")
                .status(SessionStatus.COMPLETED)
                .matchedAt(LocalDateTime.now())
                .user1(user1)
                .user2(user2)
                .build();
        PageRequest pageable = PageRequest.of(0, 10);

        when(authentication.getName()).thenReturn(user2.getEmail());
        when(userService.findByEmail(user2.getEmail())).thenReturn(Optional.of(user2));
        when(sessionService.getHistory(2L, pageable)).thenReturn(new PageImpl<>(List.of(session), pageable, 1));

        ResponseEntity<org.springframework.data.domain.Page<LearningSessionHistoryResponse>> response =
                controller.getHistory(authentication, pageable);
        LearningSessionHistoryResponse history = response.getBody().getContent().get(0);

        assertEquals(1L, history.getPeerId());
        assertEquals("first", history.getPeerUsername());
    }

    @Test
    void getTokenReturnsSessionTokenResponseForParticipantA() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L)
                .channelName("room-100")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("topic")
                .status(SessionStatus.MATCHED)
                .matchedAt(LocalDateTime.now())
                .user1(user1)
                .user2(user2)
                .user1Uid(1)
                .user2Uid(2)
                .build();

        when(authentication.getName()).thenReturn(user1.getEmail());
        when(userService.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(sessionService.findById(100L)).thenReturn(Optional.of(session));
        when(sessionService.generateTokenForSession(100L, 1L)).thenReturn(SessionTokenDTO.builder()
                .token("mock-agora-token-1")
                .channelName("room-100")
                .uid(1)
                .build());

        ResponseEntity<SessionTokenDTO> response =
                controller.getToken(100L, authentication);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("mock-agora-token-1", response.getBody().getToken());
        assertEquals("room-100", response.getBody().getChannelName());
        assertEquals(1, response.getBody().getUid());
    }

    @Test
    void getTokenReturnsSessionTokenResponseForParticipantB() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L)
                .channelName("room-100")
                .levelSnapshot(JapaneseLevel.N5)
                .tagSnapshot("topic")
                .status(SessionStatus.IN_PROGRESS)
                .matchedAt(LocalDateTime.now())
                .user1(user1)
                .user2(user2)
                .user1Uid(1)
                .user2Uid(2)
                .build();

        when(authentication.getName()).thenReturn(user2.getEmail());
        when(userService.findByEmail(user2.getEmail())).thenReturn(Optional.of(user2));
        when(sessionService.findById(100L)).thenReturn(Optional.of(session));
        when(sessionService.generateTokenForSession(100L, 2L)).thenReturn(SessionTokenDTO.builder()
                .token("mock-agora-token-2")
                .channelName("room-100")
                .uid(2)
                .build());

        ResponseEntity<SessionTokenDTO> response =
                controller.getToken(100L, authentication);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("mock-agora-token-2", response.getBody().getToken());
        assertEquals("room-100", response.getBody().getChannelName());
        assertEquals(2, response.getBody().getUid());
    }

    @Test
    void getSessionReturnsDtoForParticipant() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L)
                .channelName("room-100")
                .levelSnapshot(JapaneseLevel.N4)
                .tagSnapshot("travel")
                .status(SessionStatus.MATCHED)
                .matchedAt(LocalDateTime.now())
                .user1(user1)
                .user2(user2)
                .user1Uid(1)
                .user2Uid(2)
                .build();

        when(authentication.getName()).thenReturn(user1.getEmail());
        when(userService.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(sessionService.findById(100L)).thenReturn(Optional.of(session));

        ResponseEntity<com.example.videocall_marching_language.dto.session.LearningSessionResponse> response =
                controller.getSession(100L, authentication);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(100L, response.getBody().getId());
        assertEquals("room-100", response.getBody().getChannelName());
        assertEquals(JapaneseLevel.N4, response.getBody().getLevelSnapshot());
    }

    @Test
    void getSessionReturns403ForNonParticipant() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        User nonParticipant = user(3L, "user3");
        LearningSession session = LearningSession.builder()
                .id(100L)
                .channelName("room-100")
                .levelSnapshot(JapaneseLevel.N4)
                .tagSnapshot("travel")
                .status(SessionStatus.MATCHED)
                .matchedAt(LocalDateTime.now())
                .user1(user1)
                .user2(user2)
                .build();

        when(authentication.getName()).thenReturn(nonParticipant.getEmail());
        when(userService.findByEmail(nonParticipant.getEmail())).thenReturn(Optional.of(nonParticipant));
        when(sessionService.findById(100L)).thenReturn(Optional.of(session));

        ResponseEntity<com.example.videocall_marching_language.dto.session.LearningSessionResponse> response =
                controller.getSession(100L, authentication);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void getSessionReturns404ForNonExistentSession() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");

        when(authentication.getName()).thenReturn(user1.getEmail());
        when(userService.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(sessionService.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<com.example.videocall_marching_language.dto.session.LearningSessionResponse> response =
                controller.getSession(999L, authentication);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getTokenReturns409ForTerminalSession() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user1 = user(1L, "user1");

        when(authentication.getName()).thenReturn(user1.getEmail());
        when(userService.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(sessionService.generateTokenForSession(100L, 1L))
                .thenThrow(new com.example.videocall_marching_language.exception.SessionConflictException(
                        com.example.videocall_marching_language.exception.SessionConflictException.ConflictType.TERMINAL_STATE,
                        "Session is in terminal state"));

        ResponseEntity<SessionTokenDTO> response =
                controller.getToken(100L, authentication);

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void getTokenReturns403ForAuthenticatedNonParticipant() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user = user(3L, "outsider");
        when(authentication.getName()).thenReturn(user.getEmail());
        when(userService.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(sessionService.generateTokenForSession(100L, 3L))
                .thenThrow(new com.example.videocall_marching_language.exception.SessionAccessDeniedException("not participant"));

        assertEquals(403, controller.getToken(100L, authentication).getStatusCode().value());
    }

    @Test
    void getTokenReturns404ForMissingSession() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user = user(1L, "user1");
        when(authentication.getName()).thenReturn(user.getEmail());
        when(userService.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(sessionService.generateTokenForSession(404L, 1L))
                .thenThrow(new com.example.videocall_marching_language.exception.SessionNotFoundException("missing"));

        assertEquals(404, controller.getToken(404L, authentication).getStatusCode().value());
    }

    @Test
    void activeParticipantCanRefreshTokenWithoutClientOverrides() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user = user(1L, "user1");
        when(authentication.getName()).thenReturn(user.getEmail());
        when(userService.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(sessionService.generateTokenForSession(100L, 1L))
                .thenReturn(SessionTokenDTO.builder().token("first").channelName("server-room").uid(11).build())
                .thenReturn(SessionTokenDTO.builder().token("refreshed").channelName("server-room").uid(11).build());

        assertEquals("first", controller.getToken(100L, authentication).getBody().getToken());
        assertEquals("refreshed", controller.getToken(100L, authentication).getBody().getToken());
        verify(sessionService, times(2)).generateTokenForSession(100L, 1L);
    }

    @Test
    void reportJoinReturns409ForTerminalOrExpiredSession() {
        ILearningSessionService sessionService = mock(ILearningSessionService.class);
        IUserService userService = mock(IUserService.class);
        Authentication authentication = mock(Authentication.class);
        LearningSessionController controller = new LearningSessionController(sessionService, userService);
        User user = user(1L, "user1");
        when(authentication.getName()).thenReturn(user.getEmail());
        when(userService.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(sessionService.reportJoinAgora(100L, 1L)).thenThrow(new com.example.videocall_marching_language.exception.SessionConflictException(
                com.example.videocall_marching_language.exception.SessionConflictException.ConflictType.RECONNECT_DEADLINE_PASSED,
                "expired"));

        assertEquals(409, controller.reportJoinAgora(100L, authentication).getStatusCode().value());
    }

    private User user(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@example.com")
                .build();
    }
}
