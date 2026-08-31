package com.example.videocall_marching_language.controller.admin;

import com.example.videocall_marching_language.dto.admin.*;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.exception.AdminUsernameValidationException;
import com.example.videocall_marching_language.service.AdminDashboardService;
import com.example.videocall_marching_language.service.AdminRubricService;
import com.example.videocall_marching_language.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminDashboardService dashboardService;
    private final AdminRubricService rubricService;
    private final AdminUserService userService;

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("dashboard", dashboardService.getDashboard());
        return "admin/dashboard";
    }

    @GetMapping("/rubrics")
    public String listRubrics(Model model) {
        model.addAttribute("rubrics", rubricService.findAll());
        return "admin/rubrics/list";
    }

    @GetMapping("/rubrics/edit/{id}")
    public String editRubricForm(@PathVariable Long id, Model model) {
        RubricResponse rubric = rubricService.findById(id);
        model.addAttribute("rubric", rubric);
        model.addAttribute("form", new RubricUpdateForm(rubric.displayName(), rubric.description()));
        return "admin/rubrics/edit";
    }

    @PostMapping("/rubrics/edit/{id}")
    public String editRubric(@PathVariable Long id, @Valid @ModelAttribute("form") RubricUpdateForm form,
                             BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("rubric", rubricService.findById(id));
            return "admin/rubrics/edit";
        }
        rubricService.update(id, form);
        return "redirect:/admin/rubrics?updated";
    }

    @PostMapping("/rubrics/toggle/{id}")
    public String toggleRubric(@PathVariable Long id) {
        rubricService.toggleActive(id);
        return "redirect:/admin/rubrics?toggled";
    }

    @GetMapping("/users")
    public String listUsers(@RequestParam(defaultValue = "") String search,
                            @RequestParam(defaultValue = "0") int page, Model model) {
        int safePage = Math.max(0, page);
        model.addAttribute("users", userService.search(search,
                PageRequest.of(safePage, 20, Sort.by("id").ascending())));
        model.addAttribute("search", search);
        return "admin/users/list";
    }

    @GetMapping("/users/{id}")
    public String viewUser(@PathVariable Long id, Model model) {
        model.addAttribute("user", userService.findById(id));
        return "admin/users/view";
    }

    @GetMapping("/users/{id}/edit")
    public String editUserForm(@PathVariable Long id, Model model) {
        AdminUserResponse user = userService.findById(id);
        model.addAttribute("user", user);
        model.addAttribute("form", new AdminUserUpdateForm(user.username(), user.currentLevel()));
        model.addAttribute("levels", JapaneseLevel.values());
        return "admin/users/edit";
    }

    @PostMapping("/users/{id}/edit")
    public String editUser(@PathVariable Long id, @Valid @ModelAttribute("form") AdminUserUpdateForm form,
                           BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("user", userService.findById(id));
            model.addAttribute("levels", JapaneseLevel.values());
            return "admin/users/edit";
        }
        try {
            userService.update(id, form);
        } catch (AdminUsernameValidationException exception) {
            bindingResult.rejectValue("username", "username.invalid", exception.getMessage());
            model.addAttribute("user", userService.findById(id));
            model.addAttribute("levels", JapaneseLevel.values());
            return "admin/users/edit";
        }
        return "redirect:/admin/users/" + id + "?updated";
    }
}
