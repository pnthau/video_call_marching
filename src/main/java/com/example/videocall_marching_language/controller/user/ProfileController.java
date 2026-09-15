package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.LanguageOption;
import com.example.videocall_marching_language.dto.TagOptionDTO;
import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.InvalidAvatarException;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.service.audiolesson.AudioLessonService;
import com.example.videocall_marching_language.service.script.PracticeService;
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
    private final PracticeService practiceService;
    private final AudioLessonService audioLessonService;
    private final ITagRepository tagRepository;

    @GetMapping("/profile")
    public String showProfile(Authentication authentication, Model model) {
        model.addAttribute("profile", userService.getCurrentProfile(authentication.getName()));
        model.addAttribute("availableTopics", practiceService.getAvailableTopics());
        model.addAttribute("availableLanguages", LanguageOption.fromCodes(practiceService.getAvailableLanguages()));
        model.addAttribute("topicsWithCount", practiceService.getTopicsWithScriptCount());
        model.addAttribute("topicsWithCountJson", practiceService.getTopicsWithCountAsJson());

        // Dữ liệu cho Audio Criteria Modal & P2P Video Call
        model.addAttribute("audioAvailableTopics", practiceService.getAvailableTopics());
        model.addAttribute("audioAvailableLanguages", LanguageOption.fromCodes(audioLessonService.getAvailableLanguages()));
        model.addAttribute("audioTopicsWithCount", audioLessonService.getTopicsWithAudioLessonCount());
        model.addAttribute("audioTopicsWithCountJson", audioLessonService.getTopicsWithCountAsJson());

        model.addAttribute("availableTags", tagRepository.findAllForActiveCategories().stream()
                .map(tag -> new TagOptionDTO(
                        tag.getId(),
                        tag.getName(),
                        tag.getTagCategory().getType().name()))
                .toList());

        return "users/profile";
    }

    @GetMapping("/profile/edit")
    public String showEditForm(Authentication authentication, Model model) {
        UserProfileResponse profile = userService.getCurrentProfile(authentication.getName());
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername(profile.username());
        request.setProvider(profile.provider());

        model.addAttribute("updateProfileRequest", request);
        model.addAttribute("profile", profile);

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
        return "users/profile_edit";
    }
}
