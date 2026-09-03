package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.dto.admin.DashboardResponse;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardServiceImpl implements AdminDashboardService {
    private final IUserRepository userRepository;
    private final ITagRepository tagRepository;
    private final IRubricRepository rubricRepository;

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        return new DashboardResponse(userRepository.countByRole(UserRole.USER), tagRepository.count(),
                rubricRepository.countByActiveTrue());
    }
}
