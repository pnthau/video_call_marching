package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.MatchRequestDTO;
import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.dto.WaitingUserDTO;
import com.example.videocall_marching_language.service.MatchMakingService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class MatchMakingController {
    private final MatchMakingService matchMakingService;

    @MessageMapping("/join")
    public void joinQueue(MatchRequestDTO request, SimpMessageHeaderAccessor headerAccessor)
    {
        String sessionId = headerAccessor.getSessionId();
        matchMakingService.joinQueue(request,sessionId);
    }

    @MessageMapping("/cancel-search")
    public void removeQueue(WaitingUserDTO request)
    {
        matchMakingService.cancelSearch(request.getUserId(),request.getTagKey());
    }

    @MessageMapping("/end-call")
    public void leavedRoom(MatchRequestDTO request){
        matchMakingService.endCall(request.getUserId());
    }
}

