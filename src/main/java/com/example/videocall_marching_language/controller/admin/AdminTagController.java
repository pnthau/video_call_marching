package com.example.videocall_marching_language.controller.admin;

import com.example.videocall_marching_language.dto.admin.AdminTagCategoryForm;
import com.example.videocall_marching_language.dto.admin.AdminTagForm;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.service.AdminTagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/tags")
@RequiredArgsConstructor
public class AdminTagController {

    private final AdminTagService tagService;

    @GetMapping
    public String listTags(@RequestParam(defaultValue = "") String name,
                           @RequestParam(defaultValue = "") String type,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, 10, Sort.by(Sort.Direction.DESC, "id"));
        Page<?> tags = tagService.searchTags(name, type, pageable);

        if (safePage >= tags.getTotalPages() && tags.getTotalPages() > 0) {
            return "redirect:/admin/tags?name=" + encode(name) + "&type=" + encode(type) + "&page=" + (tags.getTotalPages() - 1);
        }

        model.addAttribute("tags", tags);
        model.addAttribute("categories", tagService.findAllCategories());
        model.addAttribute("categoryTypes", TagCategoryType.values());
        model.addAttribute("searchName", name);
        model.addAttribute("searchType", type);
        model.addAttribute("tagForm", new AdminTagForm());
        model.addAttribute("categoryForm", new AdminTagCategoryForm());
        model.addAttribute("editingTag", false);
        model.addAttribute("editingCategory", false);
        return "admin/tags/list";
    }

    @GetMapping("/edit/{id}")
    public String editTag(@PathVariable Long id,
                          @RequestParam(defaultValue = "") String name,
                          @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        loadListData(name, type, page, model);
        model.addAttribute("tagForm", tagService.getTagForm(id));
        model.addAttribute("editingTag", true);
        return "admin/tags/list";
    }

    @PostMapping("/save")
    public String saveTag(@Valid @ModelAttribute("tagForm") AdminTagForm form,
                          BindingResult bindingResult,
                          Model model,
                          @RequestParam(defaultValue = "") String filterName,
                          @RequestParam(defaultValue = "") String filterType,
                          @RequestParam(defaultValue = "0") int page) {
        if (bindingResult.hasErrors()) {
            loadListData(filterName, filterType, page, model);
            model.addAttribute("tagForm", form);
            model.addAttribute("editingTag", form.getId() != null);
            return "admin/tags/list";
        }
        tagService.saveTag(form);
        return redirectWithFilter(filterName, filterType, page, "tagSaved");
    }

    @PostMapping("/delete/{id}")
    public String deleteTag(@PathVariable Long id,
                            @RequestParam(defaultValue = "") String filterName,
                            @RequestParam(defaultValue = "") String filterType,
                            @RequestParam(defaultValue = "0") int page) {
        tagService.deleteTag(id);
        return redirectWithFilter(filterName, filterType, page, "tagDeleted");
    }

    @GetMapping("/categories/edit/{id}")
    public String editCategory(@PathVariable Long id,
                               @RequestParam(defaultValue = "") String name,
                               @RequestParam(defaultValue = "") String type,
                               @RequestParam(defaultValue = "0") int page,
                               Model model) {
        loadListData(name, type, page, model);
        model.addAttribute("categoryForm", tagService.getCategoryForm(id));
        model.addAttribute("editingCategory", true);
        return "admin/tags/list";
    }

    @PostMapping("/categories/save")
    public String saveCategory(@Valid @ModelAttribute("categoryForm") AdminTagCategoryForm form,
                               BindingResult bindingResult,
                               Model model,
                               @RequestParam(defaultValue = "") String filterName,
                               @RequestParam(defaultValue = "") String filterType,
                               @RequestParam(defaultValue = "0") int page) {
        if (bindingResult.hasErrors()) {
            loadListData(filterName, filterType, page, model);
            model.addAttribute("categoryForm", form);
            model.addAttribute("editingCategory", form.getId() != null);
            return "admin/tags/list";
        }
        tagService.saveCategory(form);
        return redirectWithFilter(filterName, filterType, page, "categorySaved");
    }

    @PostMapping("/categories/delete/{id}")
    public String deleteCategory(@PathVariable Long id,
                                 @RequestParam(defaultValue = "") String filterName,
                                 @RequestParam(defaultValue = "") String filterType,
                                 @RequestParam(defaultValue = "0") int page) {
        tagService.deleteCategory(id);
        return redirectWithFilter(filterName, filterType, page, "categoryDeleted");
    }

    @PostMapping("/categories/toggle/{id}")
    public String toggleCategory(@PathVariable Long id,
                                 @RequestParam(defaultValue = "") String filterName,
                                 @RequestParam(defaultValue = "") String filterType,
                                 @RequestParam(defaultValue = "0") int page) {
        tagService.toggleCategory(id);
        return redirectWithFilter(filterName, filterType, page, "categoryToggled");
    }

    private void loadListData(String name, String type, int page, Model model) {
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, 10, Sort.by(Sort.Direction.DESC, "id"));
        Page<?> tags = tagService.searchTags(name, type, pageable);
        model.addAttribute("tags", tags);
        model.addAttribute("categories", tagService.findAllCategories());
        model.addAttribute("categoryTypes", TagCategoryType.values());
        model.addAttribute("searchName", name);
        model.addAttribute("searchType", type);
        model.addAttribute("tagForm", new AdminTagForm());
        model.addAttribute("categoryForm", new AdminTagCategoryForm());
        model.addAttribute("editingTag", false);
        model.addAttribute("editingCategory", false);
    }

    private String redirectWithFilter(String name, String type, int page, String message) {
        return "redirect:/admin/tags?name=" + encode(name) + "&type=" + encode(type) + "&page=" + page + "&" + message;
    }

    private String encode(String value) {
        return value == null ? "" : value.replace(" ", "+");
    }
}
