package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.CompletionReason;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.exception.SessionAccessDeniedException;
import com.example.videocall_marching_language.exception.SessionNotFoundException;
import com.example.videocall_marching_language.repository.ILearningSessionRepository;
import com.example.videocall_marching_language.service.AgoraTokenService;
import com.example.videocall_marching_language.service.ILearningSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LearningSessionServiceImpl implements ILearningSessionService {

    private final ILearningSessionRepository learningSessionRepository;
    private final AgoraTokenService agoraTokenService;

    @Override
    @Transactional
    public synchronized LearningSession createSession(User user1, User user2, JapaneseLevel level, String tag, String channelName) {
        if (user1.getId().equals(user2.getId())) {
            throw new IllegalArgumentException("A learning session requires two different users");
        }

        Optional<LearningSession> activeSession = learningSessionRepository.findActiveSessionBetweenUsers(
                user1.getId(), user2.getId(), activeStatuses());
        if (activeSession.isPresent()) {
            return activeSession.get();
        }

        LearningSession session = LearningSession.builder()
                .channelName(channelName)
                .levelSnapshot(level)
                .tagSnapshot(tag)
                .status(SessionStatus.MATCHED)
                .matchedAt(LocalDateTime.now())
                .user1(user1)
                .user2(user2)
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
    @Transactional
    public LearningSession reportJoinAgora(Long sessionId, Long userId) {
        LearningSession session = getSessionAndValidateParticipantForUpdate(sessionId, userId);

        if (session.getStatus() == SessionStatus.ENDED) {
            return session;
        }

        boolean isUser1 = session.getUser1().getId().equals(userId);
        boolean isUser2 = session.getUser2().getId().equals(userId);

        LocalDateTime now = LocalDateTime.now();

        if (isUser1) {
            if (session.getUser1JoinedAgoraAt() == null) {
                session.setUser1JoinedAgoraAt(now);
            }
        } else if (isUser2) {
            if (session.getUser2JoinedAgoraAt() == null) {
                session.setUser2JoinedAgoraAt(now);
            }
        }

        if (session.getUser1JoinedAgoraAt() != null && session.getUser2JoinedAgoraAt() != null
                && session.getUser1LeftAgoraAt() == null && session.getUser2LeftAgoraAt() == null) {
            if (session.getStatus() == SessionStatus.MATCHED) {
                session.setStatus(SessionStatus.IN_PROGRESS);
                session.setStartedAt(now);
            }
        }

        return learningSessionRepository.save(session);
    }

    @Override
    @Transactional
    public LearningSession reportLeaveAgora(Long sessionId, Long userId) {
        LearningSession session = getSessionAndValidateParticipantForUpdate(sessionId, userId);

        if (session.getStatus() == SessionStatus.ENDED) {
            return session;
        }

        boolean isUser1 = session.getUser1().getId().equals(userId);
        boolean isUser2 = session.getUser2().getId().equals(userId);

        LocalDateTime now = LocalDateTime.now();

        if (isUser1) {
            if (session.getUser1LeftAgoraAt() == null) {
                session.setUser1LeftAgoraAt(now);
            }
        } else if (isUser2) {
            if (session.getUser2LeftAgoraAt() == null) {
                session.setUser2LeftAgoraAt(now);
            }
        }

        if (session.getUser1LeftAgoraAt() != null && session.getUser2LeftAgoraAt() != null) {
            session.setEndedAt(now);
            session.setStatus(SessionStatus.ENDED);
            session.setCompletionReason(CompletionReason.NORMAL);
            calculateOverlappingDuration(session);
        }

        return learningSessionRepository.save(session);
    }

    @Override
    @Transactional
    public void endSession(Long sessionId, Long userId, CompletionReason reason) {
        LearningSession session = getSessionAndValidateParticipantForUpdate(sessionId, userId);

        if (session.getStatus() == SessionStatus.ENDED) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        session.setEndedAt(now);
        session.setStatus(SessionStatus.ENDED);
        session.setCompletionReason(reason);

        if (session.getUser1().getId().equals(userId) && session.getUser1LeftAgoraAt() == null) {
            session.setUser1LeftAgoraAt(now);
        } else if (session.getUser2().getId().equals(userId) && session.getUser2LeftAgoraAt() == null) {
            session.setUser2LeftAgoraAt(now);
        }

        calculateOverlappingDuration(session);
        learningSessionRepository.save(session);
    }

    @Override
    @Transactional(readOnly = true)
    public String generateTokenForSession(Long sessionId, Long userId) {
        LearningSession session = getSessionAndValidateParticipant(sessionId, userId);

        if (session.getStatus() != SessionStatus.MATCHED && session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new SessionAccessDeniedException("Session is not active for token generation");
        }

        boolean isUser1 = session.getUser1().getId().equals(userId);
        int uid = isUser1 ? session.getUser1().getId().intValue() : session.getUser2().getId().intValue();

        return agoraTokenService.generateToken(session.getChannelName(), uid);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isParticipant(Long sessionId, Long userId) {
        return learningSessionRepository.findById(sessionId)
                .map(s -> s.getUser1().getId().equals(userId) || s.getUser2().getId().equals(userId))
                .orElse(false);
    }

    private LearningSession getSessionAndValidateParticipant(Long sessionId, Long userId) {
        LearningSession session = learningSessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        boolean isParticipant = session.getUser1().getId().equals(userId) || session.getUser2().getId().equals(userId);
        if (!isParticipant) {
            throw new SessionAccessDeniedException("User is not a participant of this session");
        }

        return session;
    }

    private LearningSession getSessionAndValidateParticipantForUpdate(Long sessionId, Long userId) {
        LearningSession session = learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        validateParticipant(session, userId);
        return session;
    }

    private void validateParticipant(LearningSession session, Long userId) {
        boolean isParticipant = session.getUser1().getId().equals(userId)
                || session.getUser2().getId().equals(userId);
        if (!isParticipant) {
            throw new SessionAccessDeniedException("User is not a participant of this session");
        }
    }

    private List<SessionStatus> activeStatuses() {
        return List.of(SessionStatus.MATCHED, SessionStatus.IN_PROGRESS);
    }

    private void calculateOverlappingDuration(LearningSession session) {
        LocalDateTime u1Start = session.getUser1JoinedAgoraAt();
        LocalDateTime u1End = session.getUser1LeftAgoraAt();
        LocalDateTime u2Start = session.getUser2JoinedAgoraAt();
        LocalDateTime u2End = session.getUser2LeftAgoraAt();

        if (u1Start == null || u2Start == null) {
            session.setOverlappingDurationSeconds(0);
            return;
        }

        LocalDateTime overlapStart = u1Start.isAfter(u2Start) ? u1Start : u2Start;
        LocalDateTime overlapEnd;

        if (u1End == null && u2End == null) {
            overlapEnd = LocalDateTime.now();
        } else if (u1End == null) {
            overlapEnd = u2End;
        } else if (u2End == null) {
            overlapEnd = u1End;
        } else {
            overlapEnd = u1End.isBefore(u2End) ? u1End : u2End;
        }

        if (overlapStart.isAfter(overlapEnd)) {
            session.setOverlappingDurationSeconds(0);
            return;
        }

        long seconds = java.time.Duration.between(overlapStart, overlapEnd).getSeconds();
        session.setOverlappingDurationSeconds((int) Math.max(0, seconds));
    }
}
