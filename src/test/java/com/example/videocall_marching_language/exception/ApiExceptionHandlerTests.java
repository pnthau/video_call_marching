package com.example.videocall_marching_language.exception;

import com.example.videocall_marching_language.controller.advice.ApiExceptionHandler;
import com.example.videocall_marching_language.exception.SessionConflictException;

import com.example.videocall_marching_language.controller.user.LearningSessionController;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.service.ILearningSessionService;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.dto.session.SessionTokenDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTests {

    private ILearningSessionService learningSessionService;
    private IUserService userService;
    private MockMvc mockMvc;
    private User user1;
    private User user2;

    @BeforeEach
    void setUp() {
        learningSessionService = mock(ILearningSessionService.class);
        userService = mock(IUserService.class);

        user1 = User.builder()
                .id(1L)
                .username("user1")
                .email("user1@example.com")
                .build();

        user2 = User.builder()
                .id(2L)
                .username("user2")
                .email("user2@example.com")
                .build();

        LearningSessionController controller =
                new LearningSessionController(learningSessionService, userService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, "unused", List.of());
    }

    @Test
    void activeSession_whenUserDoesNotExist_returnsSanitized404Json()
            throws Exception {

        String email = "missing@example.com";

        when(userService.findByEmail(email))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sessions/active")
                        .principal(auth(email)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/sessions/**"));
    }

    @Test
    void getSession_whenSessionNotFound_returnsSanitized404Json()
            throws Exception {

        when(userService.findByEmail("user1@example.com"))
                .thenReturn(Optional.of(user1));

        when(learningSessionService.findById(999L))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sessions/999")
                        .principal(auth("user1@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/sessions/**"));
    }

    @Test
    void getSession_whenNonParticipant_returnsSanitized403Json()
            throws Exception {

        User nonParticipant = User.builder()
                .id(3L)
                .username("outsider")
                .email("outsider@example.com")
                .build();

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

        when(userService.findByEmail("outsider@example.com"))
                .thenReturn(Optional.of(nonParticipant));

        when(learningSessionService.findById(100L))
                .thenReturn(Optional.of(session));

        mockMvc.perform(get("/api/sessions/100")
                        .principal(auth("outsider@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/sessions/**"));
    }

    @Test
    void getToken_whenServiceThrowsConflict_returnsSanitized409Json()
            throws Exception {

        when(userService.findByEmail("user1@example.com"))
                .thenReturn(Optional.of(user1));

        when(learningSessionService.generateTokenForSession(100L, 1L))
                .thenThrow(new SessionConflictException(
                        SessionConflictException.ConflictType.TERMINAL_STATE,
                        "Session is in terminal state"
                ));

        mockMvc.perform(get("/api/sessions/100/token")
                        .principal(auth("user1@example.com")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/sessions/**"));
    }

    @Test
    void joinAgora_whenServiceThrowsRuntime_returnsSanitized500Json()
            throws Exception {

        when(userService.findByEmail("user1@example.com"))
                .thenReturn(Optional.of(user1));

        when(learningSessionService.reportJoinAgora(100L, 1L))
                .thenThrow(new IllegalStateException("unexpected"));

        mockMvc.perform(post("/api/sessions/100/join-agora")
                        .principal(auth("user1@example.com")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi hệ thống."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/sessions/**"));
    }

    @Test
    void getSession_whenSessionIdHasInvalidType_returnsSanitized400Json()
            throws Exception {

        mockMvc.perform(get("/api/sessions/not-a-number")
                        .principal(auth("user@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request is invalid."))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/sessions/**"))
                .andExpect(content().string(not(containsString("not-a-number"))));
    }
}
