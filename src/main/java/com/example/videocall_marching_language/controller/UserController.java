package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.service.IUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/users")
public class UserController {

    private final IUserService userService;

    public UserController(IUserService userService) {
        this.userService = userService;
    }

    // DANH SÁCH USER + TÌM KIẾM + PHÂN TRANG
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

        Page<User> userPage = userService.searchUsers(
                username,
                email,
                pageable
        );

        model.addAttribute("users", userPage);
        model.addAttribute("username", username);
        model.addAttribute("email", email);

        return "users/list";
    }
    // HIỂN THỊ FORM THÊM USER
    @GetMapping("/add")
    public String showAddForm(Model model) {
        model.addAttribute("user", new User());
        model.addAttribute("levels", JapaneseLevel.values());
        return "users/add";
    }

    // XỬ LÝ THÊM USER
    @PostMapping("/add")
    public String addUser(
            @ModelAttribute("user") User user,
            Model model) {

        boolean hasError = false;

        // Kiểm tra username
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            model.addAttribute("errorUsername", "Username không được để trống");
            hasError = true;
        }

        // Kiểm tra email
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            model.addAttribute("errorEmail", "Email không được để trống");
            hasError = true;
        } else if (!user.getEmail().matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$")) {
            model.addAttribute("errorEmail", "Email không đúng định dạng");
            hasError = true;
        }

        // Kiểm tra Level (Enum JapaneseLevel)
        if (user.getCurrentLevel() == null) {
            model.addAttribute("errorLevel", "Vui lòng chọn trình độ tiếng Nhật");
            hasError = true;
        }

        // Kiểm tra Trust Score
        if (user.getTrustScore() < 0 || user.getTrustScore() > 10) {
            model.addAttribute("errorTrustScore", "Trust Score phải từ 0 đến 10");
            hasError = true;
        }

        if (hasError) {
            model.addAttribute("levels", JapaneseLevel.values());
            return "users/add";
        }

        userService.save(user);
        return "redirect:/users?success=add";
    }

    // XEM CHI TIẾT USER
    @GetMapping("/{id}")
    public String viewUser(
            @PathVariable Long id,
            Model model) {

        User user = userService.findById(id).orElse(null);
        if (user == null) {
            return "redirect:/users";
        }
        model.addAttribute("user", user);

        return "users/detail";
    }

    // HIỂN THỊ FORM SỬA USER
    @GetMapping("/edit/{id}")
    public String showEditForm(
            @PathVariable Long id,
            Model model) {

        User user = userService.findById(id).orElse(null);
        if (user == null) {
            return "redirect:/users";
        }
        model.addAttribute("user", user);
        model.addAttribute("levels", JapaneseLevel.values());
        return "users/edit";
    }

    // XỬ LÝ SỬA USER
    @PostMapping("/edit/{id}")
    public String editUser(
            @PathVariable Long id,
            @ModelAttribute("user") User user,
            Model model) {

        user.setId(id);
        userService.update(user);
        return "redirect:/users?success=edit";
    }

    // XÓA USER
    @PostMapping("/delete/{id}")
    public String deleteUser(@PathVariable Long id) {
        userService.deleteById(id);
        return "redirect:/users?success=delete";
    }
}