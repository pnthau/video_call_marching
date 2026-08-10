package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.service.AgoraTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agora")
@RequiredArgsConstructor
public class AgoraController {
    private final AgoraTokenService agoraTokenService;
    @GetMapping("/token")
    public ResponseEntity<String> getRtcToken(
            @RequestParam String channelName,
            @RequestParam int uid) {
        
        String token = agoraTokenService.generateToken(channelName, uid);
        return ResponseEntity.ok(token);
    }
}
