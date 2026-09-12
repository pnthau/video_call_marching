package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.admin.AdminTagCategoryForm;
import com.example.videocall_marching_language.dto.admin.AdminTagForm;
import com.example.videocall_marching_language.dto.admin.AdminTagResponse;
import com.example.videocall_marching_language.entity.TagCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AdminTagService {
    Page<AdminTagResponse> searchTags(String name, String type, Pageable pageable);
    List<TagCategory> findAllCategories();
    AdminTagForm getTagForm(Long id);
    void saveTag(AdminTagForm form);
    void deleteTag(Long id);

    AdminTagCategoryForm getCategoryForm(Long id);
    void saveCategory(AdminTagCategoryForm form);
    void deleteCategory(Long id);
    void toggleCategory(Long id);
}
