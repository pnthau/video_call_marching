package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.dto.script.ScriptResponse;
import com.example.videocall_marching_language.dto.TagOptionDTO;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.service.script.PracticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebController {

    @Value("${agora.app-id}")
    private String agoraAppId;

    private final IUserService userService;
    private final ITagRepository tagRepository;
    private final MatchingProperties matchingProperties;
    private final PracticeService practiceService;

    @GetMapping({"/", "/landing"})
    public String showLandingPage(Authentication authentication, Model model) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null) {
            userService.findByEmail(authentication.getName()).ifPresentOrElse(user -> {
                model.addAttribute("isAuthenticated", true);
                model.addAttribute("username", user.getUsername());
                model.addAttribute("avatarUrl", user.getAvatarUrl());
                model.addAttribute("email", user.getEmail());
            }, () -> {
                model.addAttribute("isAuthenticated", false);
            });
        } else {
            model.addAttribute("isAuthenticated", false);
        }
        return "landing";
    }

    @GetMapping("/video-call")
    public String showVideoCallPage(Authentication authentication, Model model) {
        model.addAttribute("agoraAppId", agoraAppId);

        if (authentication != null && authentication.getName() != null) {
            userService.findByEmail(authentication.getName()).ifPresent(user -> {
                model.addAttribute("currentUserId", user.getId());
                model.addAttribute("currentUserLevel", user.getCurrentLevel());
            });
        }

        List<TagOptionDTO> tags = tagRepository.findAllForActiveCategories().stream()
                .map(tag -> new TagOptionDTO(
                        tag.getId(),
                        tag.getName(),
                        tag.getTagCategory().getType().name()))
                .toList();
        model.addAttribute("tagCategoryTypes", TagCategoryType.values());
        model.addAttribute("availableTags", tags);
        model.addAttribute("adjacentLevelAfterSeconds", matchingProperties.getAdjacentLevelAfterSeconds());

        return "users/video_call";
    }

    /**
     * Stage 1: Trang đọc Script (Gate 1→2).
     * Hiển thị nội dung script + nút "Đã hiểu — Luyện với AI Sensei".
     */
    @GetMapping("/scripts/{scriptId}/study")
    public String showScriptStudyPage(
            @PathVariable Long scriptId,
            Authentication authentication,
            Model model) {

        ScriptResponse script = practiceService.findScriptById(scriptId.intValue());
        model.addAttribute("script", script);

        if (authentication != null && authentication.getName() != null) {
            userService.findByEmail(authentication.getName()).ifPresent(user -> {
                model.addAttribute("currentUserId", user.getId());
                // Mặc định cho phép luyện luôn (vì đã xóa logic ScriptReadStatus)
                model.addAttribute("alreadyRead", true);
            });
        }

        return "users/scripts/study";
    }


}
