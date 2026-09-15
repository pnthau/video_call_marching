package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.service.IUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAttributes {

    private final IUserService userService;

    @ModelAttribute("currentUri")
    public String getCurrentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute
    public void addGlobalUserAttributes(Authentication authentication, Model model) {
        boolean isAuthenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        model.addAttribute("isAuthenticated", isAuthenticated);

        if (isAuthenticated) {
            userService.findByEmail(authentication.getName()).ifPresent(user -> {
                model.addAttribute("currentUsername", user.getUsername());
                model.addAttribute("currentUserAvatar", user.getAvatarUrl());
                model.addAttribute("currentUserEmail", user.getEmail());
            });
        }
    }
}