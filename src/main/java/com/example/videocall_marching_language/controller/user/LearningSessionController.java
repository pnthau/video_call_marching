package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.session.LearningSessionHistoryResponse;
import com.example.videocall_marching_language.dto.session.LearningSessionResponse;
import com.example.videocall_marching_language.dto.session.SessionTokenResponse;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.service.ILearningSessionService;
import com.example.videocall_marching_language.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class LearningSessionController {

    private final ILearningSessionService learningSessionService;
    private final IUserService userService;

    @GetMapping("/{sessionId}")
    public ResponseEntity<LearningSessionResponse> getSession(
            @PathVariable Long sessionId,
            Authentication authentication) {
        User currentUser = userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return learningSessionService.findById(sessionId)
                .filter(session -> session.getUser1().getId().equals(currentUser.getId()) 
                        || session.getUser2().getId().equals(currentUser.getId()))
                .map(session -> ResponseEntity.ok(toResponse(session, currentUser.getId())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{sessionId}/token")
    public ResponseEntity<SessionTokenResponse> getToken(
            @PathVariable Long sessionId,
            Authentication authentication) {
        User currentUser = userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return learningSessionService.findById(sessionId)
                .filter(session -> session.getUser1().getId().equals(currentUser.getId()) 
                        || session.getUser2().getId().equals(currentUser.getId()))
                .map(session -> {
                    String token = learningSessionService.generateTokenForSession(sessionId, currentUser.getId());
                    boolean isUser1 = session.getUser1().getId().equals(currentUser.getId());
                    int uid = isUser1 ? session.getUser1().getId().intValue() : session.getUser2().getId().intValue();
                    return ResponseEntity.ok(SessionTokenResponse.builder()
                            .token(token)
                            .channelName(session.getChannelName())
                            .uid(uid)
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/join-agora")
    public ResponseEntity<Void> reportJoinAgora(
            @PathVariable Long sessionId,
            Authentication authentication) {
        User currentUser = userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return learningSessionService.findById(sessionId)
                .filter(session -> session.getUser1().getId().equals(currentUser.getId()) 
                        || session.getUser2().getId().equals(currentUser.getId()))
                .map(session -> {
                    learningSessionService.reportJoinAgora(sessionId, currentUser.getId());
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/leave-agora")
    public ResponseEntity<Void> reportLeaveAgora(
            @PathVariable Long sessionId,
            Authentication authentication) {
        User currentUser = userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return learningSessionService.findById(sessionId)
                .filter(session -> session.getUser1().getId().equals(currentUser.getId()) 
                        || session.getUser2().getId().equals(currentUser.getId()))
                .map(session -> {
                    learningSessionService.reportLeaveAgora(sessionId, currentUser.getId());
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public ResponseEntity<Page<LearningSessionHistoryResponse>> getHistory(
            Authentication authentication,
            @PageableDefault(size = 10) Pageable pageable) {
        User currentUser = userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Page<LearningSessionHistoryResponse> history = learningSessionService.getHistory(currentUser.getId(), pageable)
                .map(session -> toHistoryResponse(session, currentUser.getId()));

        return ResponseEntity.ok(history);
    }

    private LearningSessionResponse toResponse(com.example.videocall_marching_language.entity.LearningSession session, Long currentUserId) {
        return LearningSessionResponse.builder()
                .id(session.getId())
                .channelName(session.getChannelName())
                .levelSnapshot(session.getLevelSnapshot())
                .tagSnapshot(session.getTagSnapshot())
                .status(session.getStatus())
                .matchedAt(session.getMatchedAt())
                .startedAt(session.getStartedAt())
                .endedAt(session.getEndedAt())
                .overlappingDurationSeconds(session.getOverlappingDurationSeconds())
                .completionReason(session.getCompletionReason())
                .user1Id(session.getUser1().getId())
                .user1Username(session.getUser1().getUsername())
                .user2Id(session.getUser2().getId())
                .user2Username(session.getUser2().getUsername())
                .currentUserId(currentUserId)
                .build();
    }

    private LearningSessionHistoryResponse toHistoryResponse(
            com.example.videocall_marching_language.entity.LearningSession session,
            Long currentUserId) {
        boolean isUser1 = session.getUser1().getId().equals(currentUserId);
        Long peerId = isUser1 ? session.getUser2().getId() : session.getUser1().getId();
        String peerUsername = isUser1 ? session.getUser2().getUsername() : session.getUser1().getUsername();

        return LearningSessionHistoryResponse.builder()
                .id(session.getId())
                .channelName(session.getChannelName())
                .levelSnapshot(session.getLevelSnapshot())
                .tagSnapshot(session.getTagSnapshot())
                .status(session.getStatus())
                .matchedAt(session.getMatchedAt())
                .endedAt(session.getEndedAt())
                .overlappingDurationSeconds(session.getOverlappingDurationSeconds())
                .completionReason(session.getCompletionReason())
                .peerId(peerId)
                .peerUsername(peerUsername)
                .build();
    }
}
