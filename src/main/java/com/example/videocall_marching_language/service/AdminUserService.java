package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.admin.AdminUserResponse;
import com.example.videocall_marching_language.dto.admin.AdminUserUpdateForm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminUserService {
    Page<AdminUserResponse> search(String search, Pageable pageable);
    AdminUserResponse findById(Long id);
    AdminUserResponse update(Long id, AdminUserUpdateForm form);
}
