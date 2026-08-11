package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.user.RegisterRequest;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.exception.DuplicatePhoneNumberException;
import com.example.videocall_marching_language.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @GetMapping("/register")
    public String showRegisterForm(Model model) {
        if (!model.containsAttribute("registerRequest")) {
            model.addAttribute("registerRequest", new RegisterRequest());
        }
        model.addAttribute("levels", JapaneseLevel.values());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(
            @Valid @ModelAttribute("registerRequest") RegisterRequest request,
            BindingResult bindingResult,
            Model model
    ) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            bindingResult.rejectValue("confirmPassword", "password.mismatch", "Mật khẩu xác nhận không khớp");
        }

        if (!bindingResult.hasErrors()) {
            try {
                userService.register(request);
                return "redirect:/login?registered";
            } catch (DuplicatePhoneNumberException exception) {
                bindingResult.rejectValue("phoneNumber", "phone.duplicate", exception.getMessage());
            } catch (IllegalArgumentException exception) {
                bindingResult.rejectValue("phoneNumber", "phone.invalid", exception.getMessage());
            }
        }

        model.addAttribute("levels", JapaneseLevel.values());
        return "auth/register";
    }

    @GetMapping("/login")
    public String showLoginPage() {
        return "auth/login";
    }
}
