package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.AvatarStorageService;
import com.example.videocall_marching_language.service.AvatarUploadResult;
import com.example.videocall_marching_language.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    private final IUserRepository userRepository;
    private final AvatarStorageService avatarStorageService;

    // ==================== PROFILE MANAGEMENT ====================

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentProfile(String email) {
        return toResponse(findByEmail(email));
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentProfile(String email, UpdateProfileRequest request) {
        User user = findByEmail(email);
        user.setUsername(request.getUsername().trim());
        user.setCurrentLevel(request.getCurrentLevel());

        if (request.getAvatar() != null && !request.getAvatar().isEmpty()) {
            String previousAvatarPublicId = user.getAvatarPublicId();
            AvatarUploadResult uploadedAvatar = avatarStorageService.upload(request.getAvatar());
            user.setAvatarUrl(uploadedAvatar.secureUrl());
            user.setAvatarPublicId(uploadedAvatar.publicId());
            deleteOldAvatarAfterCommit(previousAvatarPublicId);
        }
        return toResponse(userRepository.save(user));
    }

    // ==================== CRUD OPERATIONS ====================

    @Override
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Override
    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User update(User user) {
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException("Không tìm thấy người dùng với ID: " + id);
        }
        userRepository.deleteById(id);
    }

    // ==================== SEARCH & PAGINATION ====================

    @Override
    @Transactional(readOnly = true)
    public Page<User> searchUsers(String username, Pageable pageable) {
        if (username == null || username.trim().isEmpty()) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByUsernameContainingIgnoreCase(username.trim(), pageable);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy tài khoản"));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getCurrentLevel(),
                user.getTrustScore(),
                user.getAvatarUrl(),
                user.getRole()
        );
    }

    private void deleteOldAvatarAfterCommit(String previousAvatarPublicId) {
        if (previousAvatarPublicId == null || previousAvatarPublicId.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            avatarStorageService.delete(previousAvatarPublicId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                avatarStorageService.delete(previousAvatarPublicId);
            }
        });
    }
}