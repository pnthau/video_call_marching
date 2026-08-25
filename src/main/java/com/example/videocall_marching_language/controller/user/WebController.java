package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.TagOptionDTO;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebController {

    @Value("${agora.app-id}")
    private String agoraAppId;

    private final IUserService userService;
    private final ITagRepository tagRepository;

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

        return "users/video_call";
    }
}
