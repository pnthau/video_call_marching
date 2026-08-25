package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.MatchRequestDTO;
import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.dto.WaitingUserDTO;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@RequiredArgsConstructor
public class MatchMakingService {
    private final IUserRepository userRepository;
    private final ITagRepository tagRepository;
    private final ILearningSessionService learningSessionService;

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<WaitingUserDTO>> queueTags = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, MatchResultDTO> matchResults = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, WaitingUserDTO> sessionUserMap = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate simpMessagingTemplate;

    public void joinQueue(MatchRequestDTO request, String sessionId) {
        Long userId = request.getUserId();
        Tag topicTag = getTagForType(request.getTopicTagId(), TagCategoryType.TOPIC);
        Tag levelTag = getTagForType(request.getLevelTagId(), TagCategoryType.LEVEL);
        Tag activityTag = getTagForType(request.getActivityTagId(), TagCategoryType.ACTIVITY);
        String tagKey = topicTag.getId() + ":" + levelTag.getId() + ":" + activityTag.getId();
        String tagSnapshot = "Chủ đề: " + topicTag.getName()
                + " | Trình độ: " + levelTag.getName()
                + " | Hình thức: " + activityTag.getName();
        JapaneseLevel level = request.getLevel();
        User currentUser = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("Không tìm thấy User!"));

        queueTags.putIfAbsent(tagKey, new ConcurrentLinkedQueue<>());
        ConcurrentLinkedQueue<WaitingUserDTO> queueWaitingUser = queueTags.get(tagKey);

        WaitingUserDTO waitingUser = queueWaitingUser.stream()
                .filter(u -> !u.getUserId().equals(userId))
                .findFirst()
                .orElse(null);

        if (waitingUser != null) {
            queueWaitingUser.remove(waitingUser);
            String channelName = "room_" + UUID.randomUUID().toString().substring(0, 8);

            User waitingUserEntity = userRepository.findById(waitingUser.getUserId())
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy User đang chờ!"));

            LearningSession session = learningSessionService.createSession(
                    currentUser, waitingUserEntity, level, tagSnapshot, channelName);
            channelName = session.getChannelName();
            tagSnapshot = session.getTagSnapshot();
            level = session.getLevelSnapshot();

            MatchResultDTO resultForCurrent = MatchResultDTO.builder()
                    .status("MATCHED")
                    .channelName(channelName)
                    .peerId(waitingUser.getUserId())
                    .peerUserName(waitingUser.getUsername())
                    .sessionId(session.getId())
                    .levelSnapshot(level)
                    .tagSnapshot(tagSnapshot)
                    .build();

            MatchResultDTO resultForWaiting = MatchResultDTO.builder()
                    .status("MATCHED")
                    .channelName(channelName)
                    .peerId(currentUser.getId())
                    .peerUserName(currentUser.getUsername())
                    .sessionId(session.getId())
                    .levelSnapshot(level)
                    .tagSnapshot(tagSnapshot)
                    .build();

            matchResults.put(userId, resultForCurrent);
            matchResults.put(waitingUser.getUserId(), resultForWaiting);

            simpMessagingTemplate.convertAndSend("/topic/match/" + userId, resultForCurrent);
            simpMessagingTemplate.convertAndSend("/topic/match/" + waitingUser.getUserId(), resultForWaiting);

        } else {
            WaitingUserDTO newWaitingUser = WaitingUserDTO.builder()
                    .userId(currentUser.getId())
                    .username(currentUser.getUsername())
                    .tagKey(tagKey)
                    .joinedTimestamp(System.currentTimeMillis())
                    .build();
            sessionUserMap.put(sessionId, newWaitingUser);
            boolean alreadyInQueue = queueWaitingUser.stream().anyMatch(u -> u.getUserId().equals(userId));
            if (!alreadyInQueue) {
                queueWaitingUser.add(newWaitingUser);
            }

            MatchResultDTO waitingResult = MatchResultDTO.builder()
                    .status("WAITING")
                    .build();

            matchResults.put(userId, waitingResult);
            simpMessagingTemplate.convertAndSend("/topic/match/" + userId, waitingResult);
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

    public void cancelSearch(Long userId, String tagKey) {
        matchResults.remove(userId);
        if (queueTags.containsKey(tagKey)) {
            queueTags.get(tagKey).removeIf(u -> u.getUserId().equals(userId));
        }
    }

    public void endCall(Long userId) {
        MatchResultDTO currentUserLeaved = matchResults.get(userId);

        if (currentUserLeaved != null && "MATCHED".equals(currentUserLeaved.getStatus()) && currentUserLeaved.getSessionId() != null) {
            try {
                learningSessionService.endSession(currentUserLeaved.getSessionId(), userId,
                    com.example.videocall_marching_language.enums.CompletionReason.NORMAL);
            } catch (Exception e) {
                // Log error but continue with in-memory cleanup
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

        WaitingUserDTO disconnectedUser = sessionUserMap.get(sessionId);
        if (disconnectedUser == null) {
            return;
        }

        sessionUserMap.remove(sessionId);

        MatchResultDTO currentStatus = matchResults.get(disconnectedUser.getUserId());
        if (currentStatus == null) {
            return;
        }

        if ("WAITING".equals(currentStatus.getStatus())) {
            cancelSearch(disconnectedUser.getUserId(), disconnectedUser.getTagKey());
        } else if ("MATCHED".equals(currentStatus.getStatus())) {
            Long peerId = currentStatus.getPeerId();
            Long sessionIdFromResult = currentStatus.getSessionId();
            matchResults.remove(peerId);
            matchResults.remove(disconnectedUser.getUserId());

            if (sessionIdFromResult != null) {
                try {
                    learningSessionService.endSession(sessionIdFromResult, disconnectedUser.getUserId(),
                        com.example.videocall_marching_language.enums.CompletionReason.PEER_LEFT);
                } catch (Exception e) {
                    // Log error but continue with in-memory cleanup
                }
            }

            MatchResultDTO cancelResult = MatchResultDTO.builder()
                    .status("PEER_DISCONNECTED")
                    .build();
            simpMessagingTemplate.convertAndSend("/topic/match/" + peerId, cancelResult);
        }
    }
}
