package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.dto.admin.UserRequestDTO;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.service.IUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final IUserService userService;

    // 1. DANH SÁCH USER + TÌM KIẾM + PHÂN TRANG
    @GetMapping
    public String listUsers(
            @RequestParam(defaultValue = "") String username,
            @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        if (page < 0) {
            page = 0;
        }

        Pageable pageable = PageRequest.of(page, 5);
        Page<User> userPage = userService.searchUsers(username, email, pageable);

        model.addAttribute("users", userPage);
        model.addAttribute("username", username);
        model.addAttribute("email", email);

        return "admin/users/list";
    }

    // 2. HIỂN THỊ FORM THÊM USER
    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("user", new UserRequestDTO());
        model.addAttribute("levels", JapaneseLevel.values());
        model.addAttribute("isView", false);
        return "admin/users/form";
    }

    // 3. XỬ LÝ THÊM USER (CÓ BEAN VALIDATION)
    @PostMapping("/add")
    public String addUser(
            @Valid @ModelAttribute("user") UserRequestDTO dto,
            BindingResult bindingResult,
            Model model) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("levels", JapaneseLevel.values());
            model.addAttribute("isView", false);
            return "admin/users/form";
        }

        User user = mapToEntity(dto);
        userService.save(user);
        return "redirect:/admin/users?success=add";
    }

    // 4. XEM CHI TIẾT USER (CHẾ ĐỘ READONLY)
    @GetMapping("/{id}")
    public String viewUser(
            @PathVariable Long id,
            Model model) {

        User user = userService.findById(id).orElse(null);
        if (user == null) {
            return "redirect:/admin/users";
        }

        model.addAttribute("user", mapToDTO(user));
        model.addAttribute("levels", JapaneseLevel.values());
        model.addAttribute("isView", true);
        return "admin/users/form";
    }

    // 5. HIỂN THỊ FORM SỬA USER
    @GetMapping("/edit/{id}")
    public String showEditForm(
            @PathVariable Long id,
            Model model) {

        User user = userService.findById(id).orElse(null);
        if (user == null) {
            return "redirect:/admin/users";
        }

        model.addAttribute("user", mapToDTO(user));
        model.addAttribute("levels", JapaneseLevel.values());
        model.addAttribute("isView", false);
        return "admin/users/form";
    }

    // 6. XỬ LÝ SỬA USER (CÓ BEAN VALIDATION)
    @PostMapping("/edit/{id}")
    public String editUser(
            @PathVariable Long id,
            @Valid @ModelAttribute("user") UserRequestDTO dto,
            BindingResult bindingResult,
            Model model) {

        dto.setId(id);

        if (bindingResult.hasErrors()) {
            model.addAttribute("levels", JapaneseLevel.values());
            model.addAttribute("isView", false);
            return "admin/users/form";
        }

        User existingUser = userService.findById(id).orElse(null);
        if (existingUser == null) {
            return "redirect:/admin/users";
        }

        existingUser.setUsername(dto.getUsername());
        existingUser.setEmail(dto.getEmail());
        existingUser.setCurrentLevel(dto.getCurrentLevel());
        existingUser.setTrustScore(dto.getTrustScore());

        userService.update(existingUser);
        return "redirect:/admin/users?success=edit";
    }

    // 7. XÓA USER
    @PostMapping("/delete/{id}")
    public String deleteUser(@PathVariable Long id) {
        userService.deleteById(id);
        return "redirect:/admin/users?success=delete";
    }

    private UserRequestDTO mapToDTO(User user) {
        return UserRequestDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .currentLevel(user.getCurrentLevel())
                .trustScore(user.getTrustScore())
                .build();
    }

    private User mapToEntity(UserRequestDTO dto) {
        return User.builder()
                .id(dto.getId())
                .username(dto.getUsername())
                .email(dto.getEmail())
                .currentLevel(dto.getCurrentLevel())
                .trustScore(dto.getTrustScore())
                .build();
    }
}