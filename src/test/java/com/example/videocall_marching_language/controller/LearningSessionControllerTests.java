package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.controller.user.LearningSessionController;
import com.example.videocall_marching_language.dto.session.LearningSessionHistoryResponse;
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
import static org.mockito.Mockito.when;

class LearningSessionControllerTests {

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
                .status(SessionStatus.ENDED)
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

    private User user(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@example.com")
                .build();
    }
}
