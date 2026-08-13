package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.MatchRequestDTO;
import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.dto.WaitingUserDTO;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.repository.IUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@RequiredArgsConstructor
public class MatchMakingService {
    private final IUserRepository userRepository;

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<WaitingUserDTO>> queueTags = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Long, MatchResultDTO> matchResults = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, WaitingUserDTO> sessionUserMap = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate simpMessagingTemplate;

    public  void joinQueue(MatchRequestDTO request,String sessionId)
    {
        Long userId = request.getUserId();
        String tagKey = request.getTagKey();
        User currentUser = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("Không tìm thấy User!"));

        queueTags.putIfAbsent(tagKey,new ConcurrentLinkedQueue<>());
        ConcurrentLinkedQueue<WaitingUserDTO> queueWaitingUser = queueTags.get(tagKey);

        WaitingUserDTO waitingUser = queueWaitingUser.stream()
                .filter(u -> !u.getUserId().equals(userId))
                .findFirst()
                .orElse(null);


        if(waitingUser != null)
        {
            queueWaitingUser.remove(waitingUser);
            String channelName = "room_" + UUID.randomUUID().toString().substring(0,8);

            MatchResultDTO resultForCurrent = MatchResultDTO.builder()
                    .status("MATCHED")
                .channelName(channelName)
                .peerId(waitingUser.getUserId())
                .peerUserName(waitingUser.getUsername())
                .build();

            MatchResultDTO resultForWaiting = MatchResultDTO.builder()
                    .status("MATCHED")
                    .channelName(channelName)
                    .peerId(currentUser.getId())
                    .peerUserName(currentUser.getUsername())
                    .build();

            matchResults.put(userId, resultForCurrent);
            matchResults.put(waitingUser.getUserId(), resultForWaiting);

            //simpMessagingTemplate
            //        .convertAndSendToUser(userId.toString(),"/queue/match",resultForCurrent);
            // simpMessagingTemplate
            //        .convertAndSendToUser(waitingUser.getUserId().toString(),"/queue/match",resultForWaiting);

            simpMessagingTemplate.convertAndSend("/topic/match/" + userId,resultForCurrent);
            simpMessagingTemplate.convertAndSend("/topic/match/" + waitingUser.getUserId(),resultForWaiting);

        }
        else{
            WaitingUserDTO newWaitingUser = WaitingUserDTO.builder()
                    .userId(currentUser.getId())
                    .username(currentUser.getUsername())
                    .tagKey(tagKey)
                    .joinedTimestamp(System.currentTimeMillis())
                    .build();
            sessionUserMap.put(sessionId,newWaitingUser);
            boolean alreadyInQueue = queueWaitingUser.stream().anyMatch(u -> u.getUserId().equals(userId));
            if (!alreadyInQueue) {
                queueWaitingUser.add(newWaitingUser);
            }

            MatchResultDTO waitingResult = MatchResultDTO.builder()
                    .status("WAITING")
                    .build();

            matchResults.put(userId, waitingResult);
            //simpMessagingTemplate.convertAndSendToUser(userId.toString(), "/queue/match", waitingResult);
            simpMessagingTemplate.convertAndSend("/topic/match/" + userId,waitingResult);
        }

    }
    public MatchResultDTO getMatchStatus(Long userId) {
        return matchResults.getOrDefault(userId, MatchResultDTO.builder().status("WAITING").build());
    }
    public void cancelSearch(Long userId, String tagKey) {
        matchResults.remove(userId);
        if (queueTags.containsKey(tagKey)) {
            queueTags.get(tagKey).removeIf(u -> u.getUserId().equals(userId));
        }
    }
    @EventListener
    public  void handleWebSocketDisconnectListener(SessionDisconnectEvent event){
        String sessionId = event.getSessionId();

        WaitingUserDTO disconnectedUser = sessionUserMap.get(sessionId);
        if (disconnectedUser == null) {
            return;
        }

        sessionUserMap.remove(sessionId);

        MatchResultDTO currentStatus = matchResults.get(disconnectedUser.getUserId());
        if(currentStatus == null)
        {
            return;
        }

        if("WAITING".equals(currentStatus.getStatus()))
        {
            cancelSearch(disconnectedUser.getUserId(), disconnectedUser.getTagKey());
        }
        else if("MATCHED".equals(currentStatus.getStatus())){
            Long peerId = currentStatus.getPeerId();
            matchResults.remove(peerId);
            matchResults.remove(disconnectedUser.getUserId());

            MatchResultDTO cancelResult = MatchResultDTO.builder()
                    .status("PEER_DISCONNECTED")
                    .build();
            simpMessagingTemplate.convertAndSend("/topic/match/" + peerId, cancelResult);
        }
    }

}
