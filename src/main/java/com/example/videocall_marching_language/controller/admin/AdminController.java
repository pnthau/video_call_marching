package com.example.videocall_marching_language.controller.admin;

import com.example.videocall_marching_language.dto.admin.*;
import com.example.videocall_marching_language.service.AdminDashboardService;
import com.example.videocall_marching_language.service.AdminRubricService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    // ── DASHBOARD ────────────────────────────────────────────────────────────

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("dashboard", dashboardService.getDashboard());
        return "admin/dashboard";
    }

    // ── RUBRICS ──────────────────────────────────────────────────────────────

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
}