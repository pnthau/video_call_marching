package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.controller.user.MatchMakingController;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.service.UserService;
import com.example.videocall_marching_language.service.MatchMakingService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchMakingControllerTests {

    @Test
    void frontendCancelSearchSendsEmptyJsonPayload() throws Exception {
        try (var script = getClass().getResourceAsStream("/static/js/video_call.js")) {
            String javascript = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(javascript.contains(
                    "stompClient.send(\"/app/cancel-search\", {}, JSON.stringify({}));"));
        }
    }

    @Test
    void frontendRecoversActiveSessionOnPageLoadWithoutLocalStorageAuthority() throws Exception {
        try (var script = getClass().getResourceAsStream("/static/js/video_call.js")) {
            String javascript = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(javascript.contains("window.addEventListener(\"load\", recoverActiveSession)"));
            assertTrue(javascript.contains("fetch('/api/sessions/active'"));
            assertTrue(javascript.contains("/app/recover-session"));
            assertTrue(javascript.contains("RECOVERY_READY"));
            assertTrue(javascript.contains("RECONNECTING"));
            assertTrue(!javascript.contains("localStorage"));
        }
    }

    @Test
    void cancelSearchWithFrontendEmptyPayloadUsesAuthenticatedUser() {
        assertAuthenticatedCancellation(UserRole.USER, 11L, "user@example.com");
    }

    @Test
    void cancelSearchWithFrontendEmptyPayloadAllowsAuthenticatedAdmin() {
        assertAuthenticatedCancellation(UserRole.ADMIN, 12L, "admin@example.com");
    }

    private void assertAuthenticatedCancellation(UserRole role, Long userId, String email) {
        MatchMakingService matchMakingService = mock(MatchMakingService.class);
        UserService userService = mock(UserService.class);
        MatchMakingController controller = new MatchMakingController(matchMakingService, userService);
        SimpMessageHeaderAccessor headers = mock(SimpMessageHeaderAccessor.class);
        Principal principal = () -> email;
        User authenticatedUser = User.builder()
                .id(userId)
                .email(email)
                .username(role.name().toLowerCase())
                .role(role)
                .build();

        when(headers.getUser()).thenReturn(principal);
        when(userService.findByEmail(email)).thenReturn(Optional.of(authenticatedUser));

        // The frontend sends `{}`; the handler deliberately has no payload argument to deserialize.
        controller.removeQueue(headers);

        verify(userService).findByEmail(email);
        verify(matchMakingService).cancelSearch(userId);
    }
}
