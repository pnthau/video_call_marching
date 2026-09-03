package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.MatchRequestDTO;
import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.service.UserService;
import com.example.videocall_marching_language.service.MatchMakingService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class MatchMakingController {
    private final MatchMakingService matchMakingService;
    private final UserService userService;

    private User getCurrentUser(SimpMessageHeaderAccessor headerAccessor) {
        String email = headerAccessor.getUser() != null ? headerAccessor.getUser().getName() : null;
        if (email == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            email = auth != null ? auth.getName() : null;
        }
        if (email == null) {
            throw new RuntimeException("User not authenticated");
        }
        return userService.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @MessageMapping("/join")
    public void joinQueue(MatchRequestDTO request, SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        User currentUser = getCurrentUser(headerAccessor);

        request.setUserId(currentUser.getId());
        request.setLevel(currentUser.getCurrentLevel());

        matchMakingService.joinQueue(request, sessionId);
    }

    @MessageMapping("/cancel-search")
    public void removeQueue(SimpMessageHeaderAccessor headerAccessor) {
        User currentUser = getCurrentUser(headerAccessor);
        matchMakingService.cancelSearch(currentUser.getId());
    }

    @MessageMapping("/recover-session")
    public void recoverSession(SimpMessageHeaderAccessor headerAccessor) {
        User currentUser = getCurrentUser(headerAccessor);
        matchMakingService.recoverSession(currentUser.getId(), headerAccessor.getSessionId());
    }

    @MessageMapping("/recovery-complete")
    public void recoveryComplete(SimpMessageHeaderAccessor headerAccessor) {
        User currentUser = getCurrentUser(headerAccessor);
        matchMakingService.notifyRecoveryComplete(currentUser.getId());
    }

    @MessageMapping("/recovery-failed")
    public void recoveryFailed(SimpMessageHeaderAccessor headerAccessor) {
        User currentUser = getCurrentUser(headerAccessor);
        matchMakingService.notifyRecoveryFailed(currentUser.getId());
    }

    @MessageMapping("/end-call")
    public void leavedRoom(MatchRequestDTO request, SimpMessageHeaderAccessor headerAccessor) {
        User currentUser = getCurrentUser(headerAccessor);
        matchMakingService.endCall(currentUser.getId());
    }
}

