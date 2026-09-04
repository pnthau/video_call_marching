package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.InvalidAvatarException;
import com.example.videocall_marching_language.service.IUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class ProfileController {

    private final IUserService userService;

    @GetMapping("/profile")
    public String showProfile(Authentication authentication, Model model) {
        model.addAttribute("profile", userService.getCurrentProfile(authentication.getName()));
        return "users/profile";
    }

    @GetMapping("/profile/edit")
    public String showEditForm(Authentication authentication, Model model) {
        UserProfileResponse profile = userService.getCurrentProfile(authentication.getName());
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername(profile.username());
        request.setCurrentLevel(profile.currentLevel());

        model.addAttribute("updateProfileRequest", request);
        model.addAttribute("profile", profile);
        model.addAttribute("levels", JapaneseLevel.values());
        return "users/profile_edit";
    }

    @PostMapping("/profile/edit")
    public String updateProfile(
            Authentication authentication,
            @Valid @ModelAttribute("updateProfileRequest") UpdateProfileRequest request,
            BindingResult bindingResult,
            Model model
    ) {
        UserProfileResponse currentProfile = userService.getCurrentProfile(authentication.getName());
        if (!bindingResult.hasErrors()) {
            try {
                userService.updateCurrentProfile(authentication.getName(), request);
                return "redirect:/profile?updated";
            } catch (InvalidAvatarException | AvatarUploadException exception) {
                bindingResult.rejectValue("avatar", "avatar.invalid", exception.getMessage());
            }
        }

        model.addAttribute("profile", currentProfile);
        model.addAttribute("levels", JapaneseLevel.values());
        return "users/profile_edit";
    }
}
