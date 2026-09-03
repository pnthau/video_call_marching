package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.dto.admin.RubricResponse;
import com.example.videocall_marching_language.dto.admin.RubricUpdateForm;
import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.exception.RubricNotFoundException;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.service.AdminRubricService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRubricServiceImpl implements AdminRubricService {
    private final IRubricRepository rubricRepository;

    @Override
    @Transactional(readOnly = true)
    public List<RubricResponse> findAll() {
        return rubricRepository.findAllByOrderByIdAsc().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RubricResponse findById(Long id) {
        return toResponse(findEntity(id));
    }

    @Override
    @Transactional
    public RubricResponse update(Long id, RubricUpdateForm form) {
        Rubric rubric = findEntity(id);
        rubric.setDisplayName(form.getDisplayName().trim());
        String description = form.getDescription();
        rubric.setDescription(description == null || description.isBlank() ? null : description.trim());
        return toResponse(rubricRepository.save(rubric));
    }

    @Override
    @Transactional
    public RubricResponse toggleActive(Long id) {
        Rubric rubric = findEntity(id);
        rubric.setActive(!rubric.isActive());
        return toResponse(rubricRepository.save(rubric));
    }

    private Rubric findEntity(Long id) {
        return rubricRepository.findById(id).orElseThrow(() -> new RubricNotFoundException(id));
    }

    private RubricResponse toResponse(Rubric rubric) {
        return new RubricResponse(rubric.getId(), rubric.getCriteria(), rubric.getDisplayName(),
                rubric.getDescription(), rubric.isActive());
    }
}
