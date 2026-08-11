package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.dto.user.RegisterRequest;
import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.enums.UserStatus;
import com.example.videocall_marching_language.exception.DuplicatePhoneNumberException;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import com.example.videocall_marching_language.repository.UserRepository;
import com.example.videocall_marching_language.service.AvatarStorageService;
import com.example.videocall_marching_language.service.UserService;
import com.example.videocall_marching_language.utils.PhoneNumberNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final AvatarStorageService avatarStorageService;

    @Override
    @Transactional
    public UserProfileResponse register(RegisterRequest request) {
        validatePassword(request.getPassword());
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp");
        }

        String normalizedPhoneNumber = phoneNumberNormalizer.normalize(request.getPhoneNumber());
        if (userRepository.existsByPhoneNumber(normalizedPhoneNumber)) {
            throw new DuplicatePhoneNumberException("Số điện thoại đã được sử dụng");
        }

        User user = User.builder()
                .username(request.getUsername().trim())
                .phoneNumber(normalizedPhoneNumber)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .currentLevel(request.getCurrentLevel())
                .trustScore(0.0f)
                .isPhoneVerified(false)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();

        try {
            return toResponse(userRepository.save(user));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicatePhoneNumberException("Số điện thoại đã được sử dụng");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentProfile(String phoneNumber) {
        return toResponse(findByPhoneNumber(phoneNumber));
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentProfile(String phoneNumber, UpdateProfileRequest request) {
        User user = findByPhoneNumber(phoneNumber);
        user.setUsername(request.getUsername().trim());
        user.setCurrentLevel(request.getCurrentLevel());

        if (request.getAvatar() != null && !request.getAvatar().isEmpty()) {
            user.setAvatarUrl(avatarStorageService.upload(request.getAvatar()));
        }
        return toResponse(userRepository.save(user));
    }

    private User findByPhoneNumber(String phoneNumber) {
        String normalizedPhoneNumber = phoneNumberNormalizer.normalize(phoneNumber);
        return userRepository.findByPhoneNumber(normalizedPhoneNumber)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy tài khoản"));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                phoneNumberNormalizer.mask(user.getPhoneNumber()),
                user.getCurrentLevel(),
                user.getTrustScore(),
                user.getAvatarUrl(),
                Boolean.TRUE.equals(user.getIsPhoneVerified()),
                user.getRole()
        );
    }

    private void validatePassword(String password) {
        if (password == null
                || password.length() < 8
                || password.length() > 72
                || !password.matches(".*[A-Za-z].*")
                || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException(
                    "Mật khẩu phải có từ 8 đến 72 ký tự, gồm ít nhất một chữ cái và một chữ số"
            );
        }
    }
}
