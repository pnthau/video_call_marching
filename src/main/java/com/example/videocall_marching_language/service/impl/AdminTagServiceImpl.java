package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.dto.admin.AdminTagCategoryForm;
import com.example.videocall_marching_language.dto.admin.AdminTagForm;
import com.example.videocall_marching_language.dto.admin.AdminTagResponse;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.exception.TagCategoryDeleteException;
import com.example.videocall_marching_language.exception.TagCategoryNotFoundException;
import com.example.videocall_marching_language.exception.TagNotFoundException;
import com.example.videocall_marching_language.repository.ITagCategoryRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.AdminTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTagServiceImpl implements AdminTagService {

    private final ITagRepository tagRepository;
    private final ITagCategoryRepository tagCategoryRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminTagResponse> searchTags(String name, String type, Pageable pageable) {
        String cleanName = name == null ? "" : name.trim();
        TagCategoryType categoryType = parseType(type);

        return tagRepository.searchForAdmin(cleanName, categoryType, pageable)
                .map(tag -> new AdminTagResponse(
                        tag.getId(),
                        tag.getName(),
                        tag.getTagCategory().getId(),
                        tag.getTagCategory().getName(),
                        tag.getTagCategory().getType()
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagCategory> findAllCategories() {
        return tagCategoryRepository.findAllByOrderByDisplayOrderAscNameAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminTagForm getTagForm(Long id) {
        Tag tag = findTag(id);
        return new AdminTagForm(tag.getId(), tag.getName(), tag.getTagCategory().getId());
    }

    @Override
    @Transactional
    public void saveTag(AdminTagForm form) {
        Tag tag = form.getId() == null ? new Tag() : findTag(form.getId());
        TagCategory tagCategory = findCategory(form.getCategoryId());

        tag.setName(form.getName().trim());
        tag.setTagCategory(tagCategory);
        tagRepository.save(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        Tag tag = findTag(id);
        tagRepository.delete(tag);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminTagCategoryForm getCategoryForm(Long id) {
        TagCategory category = findCategory(id);
        return new AdminTagCategoryForm(category.getId(), category.getName(), category.getType(),
                category.isActive(), category.getDisplayOrder());
    }

    @Override
    @Transactional
    public void saveCategory(AdminTagCategoryForm form) {
        TagCategory category = form.getId() == null ? new TagCategory() : findCategory(form.getId());

        category.setName(form.getName().trim());
        category.setType(form.getType());
        category.setActive(form.isActive());
        category.setDisplayOrder(form.getDisplayOrder());
        tagCategoryRepository.save(category);
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        TagCategory category = findCategory(id);
        if (tagRepository.existsByTagCategoryId(id)) {
            throw new TagCategoryDeleteException("Không thể xóa TagCategory đang có Tag. Hãy xóa hoặc chuyển các Tag sang Category khác trước.");
        }
        tagCategoryRepository.delete(category);
    }

    @Override
    @Transactional
    public void toggleCategory(Long id) {
        TagCategory category = findCategory(id);
        category.setActive(!category.isActive());
        tagCategoryRepository.save(category);
    }

    private Tag findTag(Long id) {
        return tagRepository.findById(id)
                .orElseThrow(() -> new TagNotFoundException(id));
    }

    private TagCategory findCategory(Long id) {
        return tagCategoryRepository.findById(id)
                .orElseThrow(() -> new TagCategoryNotFoundException(id));
    }

    private TagCategoryType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return TagCategoryType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
