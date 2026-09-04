package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.dto.MatchRequestDTO;
import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.dto.WaitingUserDTO;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.exception.SessionConflictException;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.ILearningSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchMakingService {

    private final IUserRepository userRepository;
    private final ITagRepository tagRepository;
    private final ILearningSessionService learningSessionService;
    private final MatchingProperties matchingProperties;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final TimeProvider timeProvider;

    private final ConcurrentHashMap<String, MatchingQueueEntry> userQueueMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, MatchResultDTO> matchResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MatchingQueueEntry> sessionUserMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> connectionUserMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> currentConnectionByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> recoveryConnectionByUser = new ConcurrentHashMap<>();
    private final ReentrantLock matchingLock = new ReentrantLock();

    public void joinQueue(MatchRequestDTO request, String sessionId) {
        Long userId = request.getUserId();
        registerConnection(userId, sessionId);
        Tag topicTag = getTagForType(request.getTopicTagId(), TagCategoryType.TOPIC);
        Tag levelTag = getTagForType(request.getLevelTagId(), TagCategoryType.LEVEL);
        Tag activityTag = getTagForType(request.getActivityTagId(), TagCategoryType.ACTIVITY);

        Set<Long> tagIds = Set.of(topicTag.getId(), levelTag.getId(), activityTag.getId());
        JapaneseLevel level = request.getLevel();

        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy User!"));

        if (learningSessionService.hasActiveSession(userId)) {
            sendMatchResult(userId, MatchResultDTO.builder()
                    .status("ACTIVE_SESSION_EXISTS")
                    .build());
            return;
        }

        MatchingQueueEntry entry = new MatchingQueueEntry(
                userId, currentUser.getUsername(), level, tagIds, timeProvider.instant(), sessionId);

        MatchingQueueEntry existingEntry = userQueueMap.putIfAbsent(userId.toString(), entry);
        if (existingEntry != null) {
            sendMatchResult(userId, MatchResultDTO.builder()
                    .status("ALREADY_IN_QUEUE")
                    .build());
            return;
        }

        sessionUserMap.put(sessionId, entry);

        tryMatch();

        if (userQueueMap.containsKey(userId.toString())) {
            sendMatchResult(userId, MatchResultDTO.builder()
                    .status("WAITING")
                    .build());
        }
    }

    private void tryMatch() {
        matchingLock.lock();
        try {
            // Find global anchor: oldest enqueuedAt, then smallest userId
            MatchingQueueEntry anchor = userQueueMap.values().stream()
                    .min(Comparator
                            .comparing(MatchingQueueEntry::getEnqueuedAt)
                            .thenComparing(MatchingQueueEntry::getUserId))
                    .orElse(null);

            if (anchor == null) {
                return;
            }

            if (!userQueueMap.containsKey(anchor.getUserId().toString())) {
                return;
            }

            if (learningSessionService.hasActiveSession(anchor.getUserId())) {
                userQueueMap.remove(anchor.getUserId().toString());
                tryMatch(); // Try next anchor
                return;
            }

            List<MatchingQueueEntry> candidates = userQueueMap.values().stream()
                    .filter(e -> !e.getUserId().equals(anchor.getUserId()))
                    .filter(e -> !learningSessionService.hasActiveSession(e.getUserId()))
                    .filter(e -> e.hasCommonTag(anchor))
                    .collect(Collectors.toList());

            if (candidates.isEmpty()) {
                return;
            }

            Instant now = timeProvider.instant();
            boolean canExpandLevel = !now.isBefore(anchor.getEnqueuedAt().plusSeconds(matchingProperties.getAdjacentLevelAfterSeconds()));

            List<MatchingQueueEntry> sameLevelCandidates = candidates.stream()
                    .filter(e -> e.getLevel() == anchor.getLevel())
                    .collect(Collectors.toList());

            MatchingQueueEntry bestCandidate = null;

            if (!sameLevelCandidates.isEmpty()) {
                bestCandidate = selectBestCandidate(anchor, sameLevelCandidates);
            } else if (canExpandLevel) {
                List<MatchingQueueEntry> adjacentCandidates = candidates.stream()
                        .filter(e -> e.isAdjacentLevel(anchor))
                        .collect(Collectors.toList());
                if (!adjacentCandidates.isEmpty()) {
                    bestCandidate = selectBestCandidate(anchor, adjacentCandidates);
                }
            }

            if (bestCandidate != null) {
                createSession(anchor, bestCandidate);
            }
        } finally {
            matchingLock.unlock();
        }
    }

    private MatchingQueueEntry selectBestCandidate(MatchingQueueEntry anchor, List<MatchingQueueEntry> candidates) {
        return candidates.stream()
                .min(Comparator
                        .comparingInt((MatchingQueueEntry c) -> -c.commonTagCount(anchor)) // Most common tags first
                        .thenComparing(MatchingQueueEntry::getEnqueuedAt) // Earliest enqueuedAt
                        .thenComparing(MatchingQueueEntry::getUserId)) // Smallest userId
                .orElse(null);
    }

    private void createSession(MatchingQueueEntry user1, MatchingQueueEntry user2) {
        User user1Entity = userRepository.findById(user1.getUserId())
                .orElseThrow(() -> new RuntimeException("User1 not found"));
        User user2Entity = userRepository.findById(user2.getUserId())
                .orElseThrow(() -> new RuntimeException("User2 not found"));

        String channelName = "room_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // Transactional active-session recheck is done inside createSession
            LearningSession session = learningSessionService.createSession(
                    user1Entity, user2Entity, user1.getLevel(), user1.getTagIds().toString(), channelName);

            userQueueMap.remove(user1.getUserId().toString());
            userQueueMap.remove(user2.getUserId().toString());
            sessionUserMap.remove(user1.getSessionId());
            sessionUserMap.remove(user2.getSessionId());

            MatchResultDTO resultForUser1 = MatchResultDTO.builder()
                    .status("MATCHED")
                    .channelName(session.getChannelName())
                    .peerId(user2.getUserId())
                    .peerUserName(user2.getUsername())
                    .sessionId(session.getId())
                    .levelSnapshot(session.getLevelSnapshot())
                    .tagSnapshot(session.getTagSnapshot())
                    .build();

            MatchResultDTO resultForUser2 = MatchResultDTO.builder()
                    .status("MATCHED")
                    .channelName(session.getChannelName())
                    .peerId(user1.getUserId())
                    .peerUserName(user1.getUsername())
                    .sessionId(session.getId())
                    .levelSnapshot(session.getLevelSnapshot())
                    .tagSnapshot(session.getTagSnapshot())
                    .build();

            matchResults.put(user1.getUserId(), resultForUser1);
            matchResults.put(user2.getUserId(), resultForUser2);

            sendNotificationAfterCommit(user1.getUserId(), resultForUser1);
            sendNotificationAfterCommit(user2.getUserId(), resultForUser2);

        } catch (SessionConflictException e) {
            // Persist failed due to conflict - entries remain in queue with original enqueuedAt
            log.warn("Session conflict for users {} and {}: {}", user1.getUserId(), user2.getUserId(), e.getMessage());
            String status = switch (e.getConflictType()) {
                case USER_HAS_ACTIVE_SESSION -> "USER_ACTIVE_SESSION";
                case PEER_HAS_ACTIVE_SESSION -> "PEER_ACTIVE_SESSION";
                case SESSION_ALREADY_EXISTS -> "SESSION_EXISTS";
                default -> "CONFLICT";
            };
            sendMatchResult(user1.getUserId(), MatchResultDTO.builder().status(status).build());
            sendMatchResult(user2.getUserId(), MatchResultDTO.builder().status(status).build());
        } catch (Exception e) {
            // Persist failed - entries remain in queue with original enqueuedAt
            log.error("Failed to create session for users {} and {}: {}", user1.getUserId(), user2.getUserId(), e.getMessage(), e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    public void cancelSearch(Long userId) {
        matchResults.remove(userId);
        userQueueMap.remove(userId.toString());
    }

    public void recoverSession(Long userId, String webSocketSessionId) {
        matchingLock.lock();
        try {
            if (webSocketSessionId.equals(currentConnectionByUser.get(userId))
                    && webSocketSessionId.equals(recoveryConnectionByUser.get(userId))) {
                return;
            }
            Optional<LearningSession> activeSession = learningSessionService.findActiveSessionByUserId(userId);
            if (activeSession.isEmpty()) {
                registerConnection(userId, webSocketSessionId);
                simpMessagingTemplate.convertAndSend("/topic/match/" + userId,
                        MatchResultDTO.builder().status("NO_ACTIVE_SESSION").build());
                return;
            }

            LearningSession session = activeSession.get();
            Long peerId = session.getUser1().getId().equals(userId)
                    ? session.getUser2().getId() : session.getUser1().getId();
            User peer = userRepository.findById(peerId)
                    .orElseThrow(() -> new IllegalStateException("Session peer no longer exists"));
            registerConnection(userId, webSocketSessionId);
            recoveryConnectionByUser.put(userId, webSocketSessionId);
            learningSessionService.finalizeIfNeeded(session.getId());
            LearningSession resolvedSession = learningSessionService.findById(session.getId()).orElse(session);
            if (isTerminal(resolvedSession)) {
                notifySessionEnded(userId, peer.getId(), session.getId());
                return;
            }

            LearningSession preparedSession = learningSessionService.reportLeaveAgora(session.getId(), userId);
            if (isTerminal(preparedSession)) {
                notifySessionEnded(userId, peer.getId(), session.getId());
                return;
            }
            MatchResultDTO recovery = MatchResultDTO.builder()
                    .status("RECOVERY_READY")
                    .channelName(session.getChannelName())
                    .peerId(peer.getId())
                    .peerUserName(peer.getUsername())
                    .sessionId(session.getId())
                    .levelSnapshot(session.getLevelSnapshot())
                    .tagSnapshot(session.getTagSnapshot())
                    .build();
            matchResults.put(userId, recovery);
            simpMessagingTemplate.convertAndSend("/topic/match/" + userId, recovery);
            simpMessagingTemplate.convertAndSend("/topic/match/" + peer.getId(),
                    MatchResultDTO.builder().status("PEER_RECONNECTING").sessionId(session.getId()).build());
        } catch (SessionConflictException e) {
            simpMessagingTemplate.convertAndSend("/topic/match/" + userId,
                    MatchResultDTO.builder().status("SESSION_ENDED").build());
        } finally {
            matchingLock.unlock();
        }
    }

    public void notifyRecoveryComplete(Long userId) {
        learningSessionService.findActiveSessionByUserId(userId).ifPresent(session -> {
            Long peerId = session.getUser1().getId().equals(userId)
                    ? session.getUser2().getId() : session.getUser1().getId();
            simpMessagingTemplate.convertAndSend("/topic/match/" + peerId,
                    MatchResultDTO.builder().status("PEER_RECOVERED").sessionId(session.getId()).build());
        });
    }

    public void notifyRecoveryFailed(Long userId) {
        MatchResultDTO recovery = matchResults.remove(userId);
        if (recovery != null && recovery.getPeerId() != null) {
            simpMessagingTemplate.convertAndSend("/topic/match/" + recovery.getPeerId(),
                    MatchResultDTO.builder().status("SESSION_ENDED").sessionId(recovery.getSessionId()).build());
        }
    }

    public void endCall(Long userId) {
        MatchResultDTO currentUserLeaved = matchResults.get(userId);

        if (currentUserLeaved != null && "MATCHED".equals(currentUserLeaved.getStatus()) && currentUserLeaved.getSessionId() != null) {
            try {
                learningSessionService.reportLeaveAgora(currentUserLeaved.getSessionId(), userId);
            } catch (Exception e) {
                log.error("Error ending session {}: {}", currentUserLeaved.getSessionId(), e.getMessage(), e);
            }

            matchResults.remove(userId);
            matchResults.remove(currentUserLeaved.getPeerId());

            MatchResultDTO newResult = MatchResultDTO.builder()
                    .status("PEER_DISCONNECTED")
                    .build();

            simpMessagingTemplate.convertAndSend("/topic/match/" + currentUserLeaved.getPeerId(), newResult);
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();

        Long authenticatedUserId = connectionUserMap.remove(sessionId);
        if (authenticatedUserId != null) {
            if (!currentConnectionByUser.remove(authenticatedUserId, sessionId)) {
                return;
            }
            recoveryConnectionByUser.remove(authenticatedUserId, sessionId);
            learningSessionService.findActiveSessionByUserId(authenticatedUserId).ifPresent(session -> {
                try {
                    learningSessionService.reportLeaveAgora(session.getId(), authenticatedUserId);
                    Long peerId = session.getUser1().getId().equals(authenticatedUserId)
                            ? session.getUser2().getId() : session.getUser1().getId();
                    simpMessagingTemplate.convertAndSend("/topic/match/" + peerId,
                            MatchResultDTO.builder().status("PEER_RECONNECTING").sessionId(session.getId()).build());
                } catch (RuntimeException e) {
                    log.error("Failed to persist disconnect for session {}", session.getId(), e);
                }
            });
        }

        MatchingQueueEntry disconnectedUser = sessionUserMap.get(sessionId);
        if (disconnectedUser == null) {
            return;
        }

        sessionUserMap.remove(sessionId);

        MatchResultDTO currentStatus = matchResults.get(disconnectedUser.getUserId());
        if (currentStatus == null) {
            return;
        }

        if ("WAITING".equals(currentStatus.getStatus())) {
            cancelSearch(disconnectedUser.getUserId());
        } else if ("MATCHED".equals(currentStatus.getStatus())) {
            Long peerId = currentStatus.getPeerId();
            Long sessionIdFromResult = currentStatus.getSessionId();

            // WebSocket disconnect is just a presence signal - report leave to set reconnect deadline
            // Session will be finalized by scheduler after grace period expires
            if (sessionIdFromResult != null) {
                try {
                    learningSessionService.reportLeaveAgora(sessionIdFromResult, disconnectedUser.getUserId());
                } catch (Exception e) {
                    log.error("Error reporting leave for session {} user {}: {}", sessionIdFromResult, disconnectedUser.getUserId(), e.getMessage(), e);
                    // Do not swallow: callers/monitoring must observe that presence persistence failed.
                    // Disconnect should NOT finalize session - reconnect deadline handles it
                    throw new IllegalStateException("Failed to report leave on disconnect for session " + sessionIdFromResult, e);
                }
            }

            MatchResultDTO cancelResult = MatchResultDTO.builder()
                    .status("PEER_DISCONNECTED")
                    .build();
            simpMessagingTemplate.convertAndSend("/topic/match/" + peerId, cancelResult);
        }
    }

    private Tag getTagForType(Long tagId, TagCategoryType expectedType) {
        if (tagId == null) {
            throw new IllegalArgumentException("Phải chọn đủ tag cho cả ba nhóm");
        }
        Tag tag = tagRepository.findSelectableById(tagId)
                .orElseThrow(() -> new IllegalArgumentException("Tag không tồn tại hoặc nhóm tag đã bị vô hiệu hóa"));
        if (tag.getTagCategory().getType() != expectedType) {
            throw new IllegalArgumentException("Tag không thuộc nhóm " + expectedType.name());
        }
        return tag;
    }

    private void sendMatchResult(Long userId, MatchResultDTO result) {
        matchResults.put(userId, result);
        simpMessagingTemplate.convertAndSend("/topic/match/" + userId, result);
    }

    private void registerConnection(Long userId, String webSocketSessionId) {
        String previousConnection = currentConnectionByUser.put(userId, webSocketSessionId);
        connectionUserMap.put(webSocketSessionId, userId);
        if (previousConnection != null && !previousConnection.equals(webSocketSessionId)) {
            connectionUserMap.remove(previousConnection);
            recoveryConnectionByUser.remove(userId, previousConnection);
        }
    }

    private boolean isTerminal(LearningSession session) {
        return session.getStatus() == SessionStatus.COMPLETED
                || session.getStatus() == SessionStatus.INCOMPLETE
                || session.getStatus() == SessionStatus.CANCELLED;
    }

    private void notifySessionEnded(Long userId, Long peerId, Long sessionId) {
        MatchResultDTO ended = MatchResultDTO.builder().status("SESSION_ENDED").sessionId(sessionId).build();
        simpMessagingTemplate.convertAndSend("/topic/match/" + userId, ended);
        simpMessagingTemplate.convertAndSend("/topic/match/" + peerId, ended);
    }

    private void sendNotificationAfterCommit(Long userId, MatchResultDTO result) {
        try {
            simpMessagingTemplate.convertAndSend("/topic/match/" + userId, result);
        } catch (RuntimeException e) {
            log.error("Session committed but match notification failed for user {}: {}", userId, e.getMessage(), e);
        }
    }
}
