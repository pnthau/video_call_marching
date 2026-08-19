package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTests {

    @Mock
    private IUserRepository userRepository;

    @Mock
    private AvatarStorageService avatarStorageService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(
                userRepository,
                avatarStorageService
        );
    }

    @Test
    void getCurrentProfileReturnsUserProfileWhenUserExists() {
        User user = existingUser();
        when(userRepository.findByEmail("learner@example.com")).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.getCurrentProfile("learner@example.com");

        assertEquals("learner@example.com", response.email());
        assertEquals("Học viên", response.username());
        assertEquals(JapaneseLevel.N5, response.currentLevel());
        assertEquals(UserRole.USER, response.role());
    }

    @Test
    void getCurrentProfileThrowsExceptionWhenUserNotFound() {
        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> userService.getCurrentProfile("nonexistent@example.com"));
    }

    @Test
    void updateProfileKeepsAvatarWhenNoFileIsProvided() {
        User user = existingUser();
        when(userRepository.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("Tên mới");
        request.setCurrentLevel(JapaneseLevel.N4);

        UserProfileResponse response = userService.updateCurrentProfile("learner@example.com", request);

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
        when(userRepository.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(avatarStorageService.upload(avatar)).thenReturn(
                new AvatarUploadResult("https://example.com/new.png", "videocall-marching/avatars/new")
        );
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("Học viên");
        request.setCurrentLevel(JapaneseLevel.N3);
        request.setAvatar(avatar);

        UserProfileResponse response = userService.updateCurrentProfile("learner@example.com", request);

        assertEquals("https://example.com/new.png", response.avatarUrl());
        assertEquals("videocall-marching/avatars/new", user.getAvatarPublicId());
    }

    @Test
    void updateProfileDeletesPreviousAvatarAfterReplacement() {
        User user = existingUser();
        user.setAvatarPublicId("videocall-marching/avatars/old");
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "avatar.png", "image/png", new byte[]{1, 2, 3}
        );
        when(userRepository.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(avatarStorageService.upload(avatar)).thenReturn(
                new AvatarUploadResult("https://example.com/new.png", "videocall-marching/avatars/new")
        );
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("Học viên");
        request.setCurrentLevel(JapaneseLevel.N3);
        request.setAvatar(avatar);

        userService.updateCurrentProfile("learner@example.com", request);

        verify(avatarStorageService).delete("videocall-marching/avatars/old");
    }

    @Test
    void updateProfileKeepsCurrentAvatarWhenUploadFails() {
        User user = existingUser();
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "avatar.png", "image/png", new byte[]{1, 2, 3}
        );
        when(userRepository.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(avatarStorageService.upload(avatar)).thenThrow(new AvatarUploadException("Upload thất bại", null));

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setUsername("Tên mới");
        request.setCurrentLevel(JapaneseLevel.N3);
        request.setAvatar(avatar);

        assertThrows(AvatarUploadException.class,
                () -> userService.updateCurrentProfile("learner@example.com", request));

        assertEquals("https://example.com/old.png", user.getAvatarUrl());
        verify(userRepository, never()).save(any());
    }

    private User existingUser() {
        return User.builder()
                .id(1L)
                .username("Học viên")
                .email("learner@example.com")
                .currentLevel(JapaneseLevel.N5)
                .avatarUrl("https://example.com/old.png")
                .role(UserRole.USER)
                .build();
    }
}
