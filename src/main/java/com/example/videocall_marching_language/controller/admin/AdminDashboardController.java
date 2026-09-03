package com.example.videocall_marching_language.controller.admin;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.IRubricService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final IRubricService rubricService;
    private final IUserRepository userRepository;
    private final ITagRepository tagRepository;

    // ── DASHBOARD ────────────────────────────────────────────────────────────

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("totalUsers", userRepository.count());
        model.addAttribute("totalTags", tagRepository.count());
        model.addAttribute("activeRubrics", rubricService.findAllActive().size());
        return "admin/dashboard";
    }

    // ── RUBRICS ──────────────────────────────────────────────────────────────

    @GetMapping("/rubrics")
    public String listRubrics(Model model) {
        model.addAttribute("rubrics", rubricService.findAll());
        return "admin/rubrics/list";
    }

    @GetMapping("/rubrics/edit/{id}")
    public String showEditForm(@PathVariable Long id, Model model) {
        return rubricService.findById(id)
                .map(rubric -> {
                    model.addAttribute("rubric", rubric);
                    return "admin/rubrics/edit";
                })
                .orElse("redirect:/admin/rubrics");
    }

    @PostMapping("/rubrics/edit/{id}")
    public String editRubric(@PathVariable Long id,
                             @RequestParam String displayName,
                             @RequestParam(required = false) String description) {
        rubricService.findById(id).ifPresent(rubric -> {
            rubric.setDisplayName(displayName.trim());
            rubric.setDescription(description != null ? description.trim() : null);
            rubricService.save(rubric);
        });
        return "redirect:/admin/rubrics?success=edit";
    }

    @PostMapping("/rubrics/toggle/{id}")
    public String toggleRubric(@PathVariable Long id) {
        rubricService.toggleActive(id);
        return "redirect:/admin/rubrics?success=toggle";
    }
}
