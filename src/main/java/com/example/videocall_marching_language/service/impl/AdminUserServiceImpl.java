package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.dto.admin.AdminUserResponse;
import com.example.videocall_marching_language.dto.admin.AdminUserUpdateForm;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.exception.AdminUsernameValidationException;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserServiceImpl implements AdminUserService {
    private final IUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> search(String search, Pageable pageable) {
        String cleanSearch = search == null ? "" : search.trim();
        return userRepository.searchByRole(UserRole.USER, cleanSearch, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponse findById(Long id) {
        return toResponse(findManagedUser(id));
    }

    @Override
    @Transactional
    public AdminUserResponse update(Long id, AdminUserUpdateForm form) {
        User user = findManagedUser(id);
        String username = normalizeUsername(form.getUsername());
        if (!username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
            throw new AdminUsernameValidationException("Username is already in use");
        }
        user.setUsername(username);
        user.setCurrentLevel(form.getCurrentLevel());
        return toResponse(userRepository.save(user));
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.length() < 3 || normalized.length() > 50) {
            throw new AdminUsernameValidationException("Username must contain between 3 and 50 characters");
        }
        return normalized;
    }

    private User findManagedUser(Long id) {
        return userRepository.findByIdAndRole(id, UserRole.USER)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy tài khoản user với ID: " + id));
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getCurrentLevel());
    }
}
