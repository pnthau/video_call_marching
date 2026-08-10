package com.example.videocall_marching_language.controller.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    @Value("${agora.app-id}")
    private String agoraAppId;

    @GetMapping("/video-call")
    public String showVideoCallPage(Model model) {
        model.addAttribute("agoraAppId", agoraAppId);
        return "users/video_call";
    }
}
