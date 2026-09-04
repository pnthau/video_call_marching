package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.config.LearningSessionProperties;
import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.SessionPresence;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.exception.SessionNotFoundException;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.repository.ISessionPresenceRepository;
import com.example.videocall_marching_language.service.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionFinalizer {

    private final ILearningSessionRepository learningSessionRepository;
    private final ISessionPresenceRepository sessionPresenceRepository;
    private final LearningSessionProperties learningSessionProperties;
    private final MatchingProperties matchingProperties;
    private final TimeProvider timeProvider;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeSessionIfNeeded(Long sessionId) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        if (isTerminalStatus(session.getStatus())) {
            return;
        }

        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());

        if (session.getStatus() == SessionStatus.MATCHED) {
            LocalDateTime matchTimeout = session.getMatchedAt().plusSeconds(matchingProperties.getMatchTimeoutSeconds());
            if (now.isAfter(matchTimeout) || now.isEqual(matchTimeout)) {
                finalizeSession(session, CompletionReason.MATCH_TIMEOUT, now);
                return;
            }
        }

        if (session.getReconnectDeadline() != null && now.isAfter(session.getReconnectDeadline())) {
            finalizeSession(session, CompletionReason.ONE_LEFT_TIMEOUT, now);
            return;
        }

        if (session.getStatus() == SessionStatus.IN_PROGRESS && session.getStartedAt() != null) {
            LocalDateTime maxDurationDeadline = session.getStartedAt().plusSeconds(learningSessionProperties.getMaximumDurationSeconds());
            if (now.isAfter(maxDurationDeadline) || now.isEqual(maxDurationDeadline)) {
                finalizeSession(session, CompletionReason.MAX_DURATION_REACHED, now);
            }
        }
    }

    @Transactional
    public void finalizeSession(LearningSession session, CompletionReason reason, LocalDateTime now) {
        if (isTerminalStatus(session.getStatus())) {
            return;
        }

        closeOpenIntervals(session.getId(), now);

        int totalOverlap = calculateTotalOverlap(session.getId());
        session.setAccumulatedOverlapSeconds(totalOverlap);

        session.setEndedAt(now);
        session.setCompletionReason(reason);
        session.setReconnectDeadline(null);

        boolean hasMinimumOverlap = session.getAccumulatedOverlapSeconds() >= learningSessionProperties.getMinimumOverlapSeconds();
        boolean isTechnicalFailure = reason == CompletionReason.TECHNICAL_FAILURE;

        if (session.getStatus() == SessionStatus.IN_PROGRESS) {
            if (hasMinimumOverlap && !isTechnicalFailure) {
                session.setStatus(SessionStatus.COMPLETED);
            } else {
                session.setStatus(SessionStatus.INCOMPLETE);
            }
        } else if (session.getStatus() == SessionStatus.MATCHED) {
            session.setStatus(SessionStatus.CANCELLED);
        }

        learningSessionRepository.save(session);
    }

    private void closeOpenIntervals(Long sessionId, LocalDateTime now) {
        List<SessionPresence> openIntervals = sessionPresenceRepository.findOpenIntervalsBySessionId(sessionId);
        for (SessionPresence presence : openIntervals) {
            presence.setLeftAt(now);
            sessionPresenceRepository.save(presence);
        }
    }

    private int calculateTotalOverlap(Long sessionId) {
        List<SessionPresence> closedIntervals = sessionPresenceRepository.findClosedIntervalsBySessionId(sessionId);
        int total = 0;
        for (int i = 0; i < closedIntervals.size(); i++) {
            SessionPresence p1 = closedIntervals.get(i);
            if (p1.getLeftAt() == null) continue;

            for (int j = i + 1; j < closedIntervals.size(); j++) {
                SessionPresence p2 = closedIntervals.get(j);
                if (p2.getLeftAt() == null) continue;

                if (p1.getUserId().equals(p2.getUserId())) continue;

                LocalDateTime overlapStart = p1.getJoinedAt().isAfter(p2.getJoinedAt()) ? p1.getJoinedAt() : p2.getJoinedAt();
                LocalDateTime overlapEnd = p1.getLeftAt().isBefore(p2.getLeftAt()) ? p1.getLeftAt() : p2.getLeftAt();

                if (overlapStart.isBefore(overlapEnd) || overlapStart.isEqual(overlapEnd)) {
                    long seconds = java.time.Duration.between(overlapStart, overlapEnd).getSeconds();
                    total += seconds;
                }
            }
        }
        return total;
    }

    private boolean isTerminalStatus(SessionStatus status) {
        return status == SessionStatus.COMPLETED || status == SessionStatus.INCOMPLETE || status == SessionStatus.CANCELLED;
    }
}
