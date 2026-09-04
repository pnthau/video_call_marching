package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.config.LearningSessionProperties;
import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.dto.session.SessionTokenDTO;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.SessionPresence;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.exception.SessionAccessDeniedException;
import com.example.videocall_marching_language.exception.SessionConflictException;
import com.example.videocall_marching_language.exception.SessionNotFoundException;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.repository.ISessionPresenceRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.AgoraTokenService;
import com.example.videocall_marching_language.service.AgoraUidPairGenerator;
import com.example.videocall_marching_language.service.ILearningSessionService;
import com.example.videocall_marching_language.service.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningSessionServiceImpl implements ILearningSessionService {

    private final ILearningSessionRepository learningSessionRepository;
    private final ISessionPresenceRepository sessionPresenceRepository;
    private final AgoraTokenService agoraTokenService;
    private final LearningSessionProperties learningSessionProperties;
    private final MatchingProperties matchingProperties;
    private final TimeProvider timeProvider;
    private final SessionFinalizer sessionFinalizer;
    private final IUserRepository userRepository;
    private final AgoraUidPairGenerator agoraUidPairGenerator;

    @Override
    @Transactional
    public LearningSession createSession(User user1, User user2, JapaneseLevel level, String tag, String channelName) {
        if (user1.getId().equals(user2.getId())) {
            throw new IllegalArgumentException("A learning session requires two different users");
        }

        var activeStatuses = activeStatuses();

        List<Long> participantIds = List.of(user1.getId(), user2.getId()).stream().sorted().toList();
        if (userRepository.findAllByIdForUpdate(participantIds).size() != 2) {
            throw new IllegalArgumentException("Both learning-session participants must exist");
        }

        Optional<LearningSession> activeSession = learningSessionRepository.findActiveSessionBetweenUsers(
                user1.getId(), user2.getId(), activeStatuses);
        if (activeSession.isPresent()) {
            return activeSession.get();
        }

        Optional<LearningSession> user1ActiveSession = learningSessionRepository.findActiveSessionByUserIdWithLock(
                user1.getId(), activeStatuses);
        if (user1ActiveSession.isPresent()) {
            throw new SessionConflictException(
                    SessionConflictException.ConflictType.USER_HAS_ACTIVE_SESSION,
                    "User " + user1.getId() + " already has an active session");
        }

        Optional<LearningSession> user2ActiveSession = learningSessionRepository.findActiveSessionByUserIdWithLock(
                user2.getId(), activeStatuses);
        if (user2ActiveSession.isPresent()) {
            throw new SessionConflictException(
                    SessionConflictException.ConflictType.PEER_HAS_ACTIVE_SESSION,
                    "User " + user2.getId() + " already has an active session");
        }

        AgoraUidPairGenerator.UidPair uidPair = agoraUidPairGenerator.generate(user1.getId(), user2.getId());

        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());
        LearningSession session = LearningSession.builder()
                .channelName(channelName)
                .levelSnapshot(level)
                .tagSnapshot(tag)
                .status(SessionStatus.MATCHED)
                .matchedAt(now)
                .user1(user1)
                .user2(user2)
                .user1Uid(uidPair.user1Uid())
                .user2Uid(uidPair.user2Uid())
                .accumulatedOverlapSeconds(0)
                .build();

        return learningSessionRepository.save(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LearningSession> findById(Long id) {
        return learningSessionRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LearningSession> findByChannelName(String channelName) {
        return learningSessionRepository.findByChannelName(channelName);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LearningSession> findByChannelNameAndParticipant(String channelName, Long userId) {
        return learningSessionRepository.findByChannelNameAndParticipant(channelName, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LearningSession> getHistory(Long userId, Pageable pageable) {
        return learningSessionRepository.findByUserId(userId, pageable);
    }

    @Override
    @Transactional(noRollbackFor = SessionConflictException.class)
    public LearningSession reportJoinAgora(Long sessionId, Long userId) {
        LearningSession session = getSessionForUpdate(sessionId, userId);
        validateActiveSession(session);

        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());

        if (session.getReconnectDeadline() != null && now.isAfter(session.getReconnectDeadline())) {
            sessionFinalizer.finalizeSession(session, CompletionReason.ONE_LEFT_TIMEOUT, now);
            throw new SessionConflictException(
                    SessionConflictException.ConflictType.RECONNECT_DEADLINE_PASSED,
                    "Session reconnect deadline has passed");
        }

        if (session.getStatus() == SessionStatus.MATCHED) {
            LocalDateTime matchTimeout = session.getMatchedAt().plusSeconds(matchingProperties.getMatchTimeoutSeconds());
            if (now.isAfter(matchTimeout) || now.isEqual(matchTimeout)) {
                sessionFinalizer.finalizeSession(session, CompletionReason.MATCH_TIMEOUT, now);
                throw new SessionConflictException(
                        SessionConflictException.ConflictType.MATCH_TIMEOUT,
                        "Session match timeout has passed");
            }
        }

        if (session.getStatus() == SessionStatus.IN_PROGRESS && session.getStartedAt() != null) {
            LocalDateTime maxDurationDeadline = session.getStartedAt().plusSeconds(learningSessionProperties.getMaximumDurationSeconds());
            if (now.isAfter(maxDurationDeadline) || now.isEqual(maxDurationDeadline)) {
                sessionFinalizer.finalizeSession(session, CompletionReason.MAX_DURATION_REACHED, now);
                throw new SessionConflictException(
                        SessionConflictException.ConflictType.MAX_DURATION_REACHED,
                        "Session maximum duration reached");
            }
        }

        Optional<SessionPresence> openInterval = sessionPresenceRepository.findOpenIntervalForUpdate(sessionId, userId);
        if (openInterval.isPresent()) {
            return session;
        }

        SessionPresence presence = SessionPresence.builder()
                .sessionId(sessionId)
                .userId(userId)
                .joinedAt(now)
                .build();
        sessionPresenceRepository.save(presence);

        boolean otherPresent = isOtherParticipantPresent(session, userId);
        if (otherPresent && session.getStatus() == SessionStatus.MATCHED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            session.setStartedAt(now);
        }

        if (otherPresent) {
            session.setReconnectDeadline(null);
        }

        updateAccumulatedOverlap(sessionId);
        return learningSessionRepository.save(session);
    }

    @Override
    @Transactional
    public LearningSession reportLeaveAgora(Long sessionId, Long userId) {
        LearningSession session = getSessionForUpdate(sessionId, userId);

        if (isTerminalStatus(session.getStatus())) {
            return session;
        }

        boolean isUser1 = session.getUser1().getId().equals(userId);
        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());

        Optional<SessionPresence> openInterval = sessionPresenceRepository.findOpenIntervalForUpdate(sessionId, userId);
        if (openInterval.isEmpty()) {
            return session;
        }

        SessionPresence presence = openInterval.get();
        presence.setLeftAt(now);
        sessionPresenceRepository.save(presence);

        updateAccumulatedOverlap(sessionId);

        boolean otherPresent = isOtherParticipantPresent(session, userId);
        if (!otherPresent) {
            if (session.getStatus() == SessionStatus.IN_PROGRESS) {
                sessionFinalizer.finalizeSession(session, CompletionReason.BOTH_LEFT, now);
            } else if (session.getStatus() == SessionStatus.MATCHED) {
                sessionFinalizer.finalizeSession(session, CompletionReason.CANCELLED_BY_USER, now);
            }
        } else {
            int reconnectGrace = learningSessionProperties.getReconnectGraceSeconds();
            LocalDateTime deadline = now.plusSeconds(reconnectGrace);
            session.setReconnectDeadline(deadline);
            learningSessionRepository.save(session);
        }

        return session;
    }

    @Override
    @Transactional(noRollbackFor = SessionConflictException.class)
    public SessionTokenDTO generateTokenForSession(Long sessionId, Long userId) {
        LearningSession session = getSessionForUpdate(sessionId, userId);

        if (isTerminalStatus(session.getStatus())) {
            throw new SessionConflictException(
                    SessionConflictException.ConflictType.TERMINAL_STATE,
                    "Session is in terminal state: " + session.getStatus());
        }

        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());

        if (session.getReconnectDeadline() != null) {
            if (now.isAfter(session.getReconnectDeadline())) {
                sessionFinalizer.finalizeSession(session, CompletionReason.ONE_LEFT_TIMEOUT, now);
                throw new SessionConflictException(
                        SessionConflictException.ConflictType.RECONNECT_DEADLINE_PASSED,
                        "Session reconnect deadline has passed");
            }
        }

        if (session.getStatus() == SessionStatus.MATCHED) {
            LocalDateTime matchTimeout = session.getMatchedAt().plusSeconds(matchingProperties.getMatchTimeoutSeconds());
            if (now.isAfter(matchTimeout) || now.isEqual(matchTimeout)) {
                sessionFinalizer.finalizeSession(session, CompletionReason.MATCH_TIMEOUT, now);
                throw new SessionConflictException(
                        SessionConflictException.ConflictType.MATCH_TIMEOUT,
                        "Session match timeout has passed");
            }
        }

        if (session.getStatus() == SessionStatus.IN_PROGRESS && session.getStartedAt() != null) {
            LocalDateTime maxDurationDeadline = session.getStartedAt().plusSeconds(learningSessionProperties.getMaximumDurationSeconds());
            if (now.isAfter(maxDurationDeadline) || now.isEqual(maxDurationDeadline)) {
                sessionFinalizer.finalizeSession(session, CompletionReason.MAX_DURATION_REACHED, now);
                throw new SessionConflictException(
                        SessionConflictException.ConflictType.MAX_DURATION_REACHED,
                        "Session maximum duration reached");
            }
        }

        boolean isUser1 = session.getUser1().getId().equals(userId);
        int uid = isUser1 ? session.getUser1Uid() : session.getUser2Uid();
        String channelName = session.getChannelName();

        String token = agoraTokenService.generateToken(channelName, uid);

        return SessionTokenDTO.builder()
                .token(token)
                .channelName(channelName)
                .uid(uid)
                .build();
    }

    @Override
    @Transactional
    public void endSession(Long sessionId, Long userId, CompletionReason reason) {
        LearningSession session = getSessionForUpdate(sessionId, userId);

        if (isTerminalStatus(session.getStatus())) {
            return;
        }

        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());

        Optional<SessionPresence> openInterval = sessionPresenceRepository.findOpenIntervalForUpdate(sessionId, userId);
        if (openInterval.isPresent()) {
            SessionPresence presence = openInterval.get();
            presence.setLeftAt(now);
            sessionPresenceRepository.save(presence);
        }

        updateAccumulatedOverlap(sessionId);
        sessionFinalizer.finalizeSession(session, reason, now);
    }

    @Override
    @Transactional
    public void cancelSession(Long sessionId, Long userId, CompletionReason reason) {
        LearningSession session = getSessionForUpdate(sessionId, userId);

        if (isTerminalStatus(session.getStatus())) {
            return;
        }

        if (session.getStatus() != SessionStatus.MATCHED) {
            throw new IllegalStateException("Can only cancel MATCHED sessions");
        }

        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());
        sessionFinalizer.finalizeSession(session, reason, now);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isParticipant(Long sessionId, Long userId) {
        return learningSessionRepository.findById(sessionId)
                .map(s -> s.getUser1().getId().equals(userId) || s.getUser2().getId().equals(userId))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPresence> getPresenceIntervals(Long sessionId, Long userId) {
        return sessionPresenceRepository.findBySessionIdAndUserIdOrderByJoinedAtDesc(sessionId, userId);
    }

    @Override
    @Transactional
    public void finalizeIfNeeded(Long sessionId) {
        sessionFinalizer.finalizeSessionIfNeeded(sessionId);
    }

    @Override
    public void finalizeAllActiveSessions() {
        var activeStatuses = activeStatuses();
        LocalDateTime now = LocalDateTime.ofInstant(timeProvider.instant(), timeProvider.getZone());

        List<LearningSession> pastReconnectDeadline = learningSessionRepository.findSessionsPastReconnectDeadline(activeStatuses, now);
        for (LearningSession session : pastReconnectDeadline) {
            try {
                sessionFinalizer.finalizeSessionIfNeeded(session.getId());
            } catch (Exception e) {
                log.error("Failed to finalize session {} past reconnect deadline: {}", session.getId(), e.getMessage(), e);
            }
        }

        List<LearningSession> pastMaxDuration = learningSessionRepository.findSessionsPastMaxDuration(activeStatuses, now);
        for (LearningSession session : pastMaxDuration) {
            try {
                sessionFinalizer.finalizeSessionIfNeeded(session.getId());
            } catch (Exception e) {
                log.error("Failed to finalize session {} past max duration: {}", session.getId(), e.getMessage(), e);
            }
        }

        List<LearningSession> pastMatchTimeout = learningSessionRepository.findMatchedSessionsPastTimeout(SessionStatus.MATCHED, now);
        for (LearningSession session : pastMatchTimeout) {
            try {
                sessionFinalizer.finalizeSessionIfNeeded(session.getId());
            } catch (Exception e) {
                log.error("Failed to finalize session {} past match timeout: {}", session.getId(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Transactional
    public LearningSession getSessionForUpdate(Long sessionId, Long userId) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        validateParticipant(session, userId);
        return session;
    }

    @Override
    @Transactional
    public void updateAccumulatedOverlap(Long sessionId) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        List<SessionPresence> closedIntervals = sessionPresenceRepository.findClosedIntervalsBySessionId(sessionId);
        int totalOverlap = calculateTotalOverlap(closedIntervals);
        session.setAccumulatedOverlapSeconds(totalOverlap);
        learningSessionRepository.save(session);
    }

    @Override
    @Transactional
    public void setReconnectDeadline(Long sessionId, LocalDateTime deadline) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
        session.setReconnectDeadline(deadline);
        learningSessionRepository.save(session);
    }

    @Override
    @Transactional
    public void clearReconnectDeadline(Long sessionId) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
        session.setReconnectDeadline(null);
        learningSessionRepository.save(session);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveSession(Long userId) {
        return learningSessionRepository.findByUserIdAndStatusIn(userId, activeStatuses()).stream().findAny().isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LearningSession> findActiveSessionByUserId(Long userId) {
        return learningSessionRepository.findByUserIdAndStatusIn(userId, activeStatuses()).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LearningSession> findAllActiveSessions() {
        return learningSessionRepository.findByStatusIn(activeStatuses());
    }

    private void validateActiveSession(LearningSession session) {
        if (isTerminalStatus(session.getStatus())) {
            throw new SessionConflictException(
                    SessionConflictException.ConflictType.TERMINAL_STATE,
                    "Session is in terminal state: " + session.getStatus());
        }
    }

    private void validateParticipant(LearningSession session, Long userId) {
        boolean isParticipant = session.getUser1().getId().equals(userId) || session.getUser2().getId().equals(userId);
        if (!isParticipant) {
            throw new SessionAccessDeniedException("User is not a participant of this session");
        }
    }

    private boolean isOtherParticipantPresent(LearningSession session, Long userId) {
        Long otherUserId = session.getUser1().getId().equals(userId) ? session.getUser2().getId() : session.getUser1().getId();
        return sessionPresenceRepository.findOpenIntervalForUpdate(session.getId(), otherUserId).isPresent();
    }

    private int calculateTotalOverlap(List<SessionPresence> intervals) {
        int total = 0;
        for (int i = 0; i < intervals.size(); i++) {
            SessionPresence p1 = intervals.get(i);
            if (p1.getLeftAt() == null) continue;

            for (int j = i + 1; j < intervals.size(); j++) {
                SessionPresence p2 = intervals.get(j);
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

    private List<SessionStatus> activeStatuses() {
        return List.of(SessionStatus.MATCHED, SessionStatus.IN_PROGRESS);
    }

}
