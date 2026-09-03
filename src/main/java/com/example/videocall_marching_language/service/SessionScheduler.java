package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.enums.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionScheduler {

    private final ILearningSessionService learningSessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedDelay = 30000)
    public void finalizeExpiredSessions() {
        finalizeAndNotify();
    }

    @Scheduled(fixedDelay = 60000)
    public void recoverActiveSessions() {
        finalizeAndNotify();
    }

    private void finalizeAndNotify() {
        var candidates = learningSessionService.findAllActiveSessions();
        learningSessionService.finalizeAllActiveSessions();
        for (var candidate : candidates) {
            learningSessionService.findById(candidate.getId())
                    .filter(session -> isTerminal(session.getStatus()))
                    .ifPresent(session -> notifyParticipants(session.getId(),
                            session.getUser1().getId(), session.getUser2().getId()));
        }
    }

    private void notifyParticipants(Long sessionId, Long user1Id, Long user2Id) {
        MatchResultDTO ended = MatchResultDTO.builder().status("SESSION_ENDED").sessionId(sessionId).build();
        try {
            messagingTemplate.convertAndSend("/topic/match/" + user1Id, ended);
            messagingTemplate.convertAndSend("/topic/match/" + user2Id, ended);
        } catch (RuntimeException e) {
            log.error("Session {} finalized but terminal notification failed", sessionId, e);
        }
    }

    private boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.COMPLETED
                || status == SessionStatus.INCOMPLETE
                || status == SessionStatus.CANCELLED;
    }
}
