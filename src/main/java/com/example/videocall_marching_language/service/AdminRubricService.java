package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.admin.RubricResponse;
import com.example.videocall_marching_language.dto.admin.RubricUpdateForm;

import java.util.List;

public interface AdminRubricService {
    List<RubricResponse> findAll();
    RubricResponse findById(Long id);
    RubricResponse update(Long id, RubricUpdateForm form);
    RubricResponse toggleActive(Long id);
}
