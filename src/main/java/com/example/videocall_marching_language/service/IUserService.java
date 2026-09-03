package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface IUserService {

    // ==================== PROFILE MANAGEMENT ====================

    UserProfileResponse getCurrentProfile(String email);

    UserProfileResponse updateCurrentProfile(String email, UpdateProfileRequest request);

    Optional<User> findByEmail(String email);

    // ==================== CRUD OPERATIONS ====================

    List<User> findAll();

    Optional<User> findById(Long id);

    User save(User user);

    User update(User user);

    void deleteById(Long id);

    // ==================== SEARCH & PAGINATION ====================

    Page<User> searchUsers(String username, String email, Pageable pageable);
}