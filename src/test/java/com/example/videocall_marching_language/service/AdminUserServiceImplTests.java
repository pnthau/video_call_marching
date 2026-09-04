package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.admin.AdminUserUpdateForm;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.exception.AdminUsernameValidationException;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.impl.AdminUserServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminUserServiceImplTests {
    @Test
    void safeEditPreservesRoleTrustAndOtherProtectedFields() {
        IUserRepository repository = mock(IUserRepository.class);
        AdminUserServiceImpl service = new AdminUserServiceImpl(repository);
        User user = User.builder().id(5L).username("old").email("old@example.com")
                .currentLevel(JapaneseLevel.N5).role(UserRole.USER).trustScore(8.5f)
                .avatarUrl("avatar").build();
        when(repository.findByIdAndRole(5L, UserRole.USER)).thenReturn(Optional.of(user));
        when(repository.save(user)).thenReturn(user);

        service.update(5L, new AdminUserUpdateForm("new-user", JapaneseLevel.N3));

        assertEquals(UserRole.USER, user.getRole());
        assertEquals(8.5f, user.getTrustScore());
        assertEquals("avatar", user.getAvatarUrl());
        assertEquals("old@example.com", user.getEmail());
        assertEquals("new-user", user.getUsername());
        assertEquals(JapaneseLevel.N3, user.getCurrentLevel());
    }

    @Test
    void adminRecordIsNeverFetchedThroughManagementService() {
        IUserRepository repository = mock(IUserRepository.class);
        AdminUserServiceImpl service = new AdminUserServiceImpl(repository);
        when(repository.findByIdAndRole(1L, UserRole.USER)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.findById(1L));
        verify(repository, never()).findById(1L);
    }

    @Test
    void paddedUsernameThatNormalizesBelowMinimumIsRejected() {
        IUserRepository repository = mock(IUserRepository.class);
        AdminUserServiceImpl service = new AdminUserServiceImpl(repository);
        User user = User.builder().id(5L).username("old").role(UserRole.USER).build();
        when(repository.findByIdAndRole(5L, UserRole.USER)).thenReturn(Optional.of(user));

        assertThrows(AdminUsernameValidationException.class,
                () -> service.update(5L, new AdminUserUpdateForm("  ab  ", JapaneseLevel.N3)));
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateUsernameIsRejected() {
        IUserRepository repository = mock(IUserRepository.class);
        AdminUserServiceImpl service = new AdminUserServiceImpl(repository);
        User user = User.builder().id(5L).username("old").role(UserRole.USER).build();
        when(repository.findByIdAndRole(5L, UserRole.USER)).thenReturn(Optional.of(user));
        when(repository.existsByUsername("taken")).thenReturn(true);

        assertThrows(AdminUsernameValidationException.class,
                () -> service.update(5L, new AdminUserUpdateForm(" taken ", JapaneseLevel.N3)));
        verify(repository, never()).save(any());
    }

    @Test
    void unchangedUsernameDoesNotTriggerDuplicateCheck() {
        IUserRepository repository = mock(IUserRepository.class);
        AdminUserServiceImpl service = new AdminUserServiceImpl(repository);
        User user = User.builder().id(5L).username("same-user").role(UserRole.USER).build();
        when(repository.findByIdAndRole(5L, UserRole.USER)).thenReturn(Optional.of(user));
        when(repository.save(user)).thenReturn(user);

        service.update(5L, new AdminUserUpdateForm("  same-user  ", JapaneseLevel.N3));

        verify(repository, never()).existsByUsername(anyString());
        verify(repository).save(user);
    }
}
