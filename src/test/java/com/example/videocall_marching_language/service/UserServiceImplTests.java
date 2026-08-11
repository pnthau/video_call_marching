package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.user.RegisterRequest;
import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.enums.UserStatus;
import com.example.videocall_marching_language.exception.DuplicatePhoneNumberException;
import com.example.videocall_marching_language.repository.UserRepository;
import com.example.videocall_marching_language.service.impl.UserServiceImpl;
import com.example.videocall_marching_language.utils.PhoneNumberNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AvatarStorageService avatarStorageService;

    private UserServiceImpl userService;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserServiceImpl(
                userRepository,
                passwordEncoder,
                new PhoneNumberNormalizer(),
                avatarStorageService
        );
    }

    @Test
    void registerNormalizesPhoneHashesPasswordAndUsesSafeDefaults() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        UserProfileResponse response = userService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User savedUser = captor.getValue();

        assertEquals("+84912345678", savedUser.getPhoneNumber());
        assertNotEquals("Password1", savedUser.getPasswordHash());
        assertEquals(UserRole.USER, savedUser.getRole());
        assertEquals(UserStatus.ACTIVE, savedUser.getStatus());
        assertFalse(savedUser.getIsPhoneVerified());
        assertEquals("+8491****678", response.phoneNumberMasked());
        assertEquals(JapaneseLevel.N5, response.currentLevel());
    }

    @Test
    void registerRejectsDuplicatePhoneNumber() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByPhoneNumber("+84912345678")).thenReturn(true);

        assertThrows(DuplicatePhoneNumberException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsMismatchedConfirmation() {
        RegisterRequest request = registerRequest();
        request.setConfirmPassword("Different1");

        assertThrows(IllegalArgumentException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsWeakPasswordAtServiceBoundary() {
        RegisterRequest request = registerRequest();
        request.setPassword("onlyletters");
        request.setConfirmPassword("onlyletters");

        assertThrows(IllegalArgumentException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfileKeepsAvatarWhenNoFileIsProvided() {
        User user = existingUser();
        when(userRepository.findByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("Tên mới");
        request.setCurrentLevel(JapaneseLevel.N4);

        UserProfileResponse response = userService.updateCurrentProfile("+84912345678", request);

        assertEquals("Tên mới", response.username());
        assertEquals(JapaneseLevel.N4, response.currentLevel());
        assertEquals("https://example.com/old.png", response.avatarUrl());
        verify(avatarStorageService, never()).upload(any());
    }

    @Test
    void updateProfileStoresUploadedAvatarUrl() {
        User user = existingUser();
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "avatar.png", "image/png", new byte[]{1, 2, 3}
        );
        when(userRepository.findByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));
        when(avatarStorageService.upload(avatar)).thenReturn("https://example.com/new.png");
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("Học viên");
        request.setCurrentLevel(JapaneseLevel.N3);
        request.setAvatar(avatar);

        UserProfileResponse response = userService.updateCurrentProfile("0912345678", request);

        assertEquals("https://example.com/new.png", response.avatarUrl());
    }

    private RegisterRequest registerRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("Học viên");
        request.setPhoneNumber("0912 345 678");
        request.setPassword("Password1");
        request.setConfirmPassword("Password1");
        request.setCurrentLevel(JapaneseLevel.N5);
        return request;
    }

    private User existingUser() {
        return User.builder()
                .id(1L)
                .username("Học viên")
                .phoneNumber("+84912345678")
                .passwordHash(passwordEncoder.encode("Password1"))
                .currentLevel(JapaneseLevel.N5)
                .avatarUrl("https://example.com/old.png")
                .build();
    }
}
