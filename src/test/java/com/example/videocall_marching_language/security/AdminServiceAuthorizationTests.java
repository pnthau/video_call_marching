package com.example.videocall_marching_language.security;

import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.AdminDashboardService;
import com.example.videocall_marching_language.service.AdminRubricService;
import com.example.videocall_marching_language.service.AdminUserService;
import com.example.videocall_marching_language.service.impl.AdminDashboardServiceImpl;
import com.example.videocall_marching_language.service.impl.AdminRubricServiceImpl;
import com.example.videocall_marching_language.service.impl.AdminUserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(AdminServiceAuthorizationTests.Config.class)
class AdminServiceAuthorizationTests {
    @Autowired AdminRubricService service;
    @Autowired IRubricRepository repository;
    @Autowired AdminUserService userService;
    @Autowired IUserRepository userRepository;
    @Autowired AdminDashboardService dashboardService;
    @Autowired ITagRepository tagRepository;

    @Test
    @WithMockUser(roles = "USER")
    void userIsDeniedBeforeRepositoryInvocation() {
        assertThrows(AccessDeniedException.class, () -> service.findAll());
        verify(repository, org.mockito.Mockito.never()).findAllByOrderByIdAsc();
    }

    @Test
    @WithMockUser(roles = "USER")
    void userManagementIsDeniedBeforeRepositoryInvocation() {
        assertThrows(AccessDeniedException.class, () -> userService.findById(1L));
        verify(userRepository, org.mockito.Mockito.never()).findByIdAndRole(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void dashboardIsDeniedBeforeRepositoryInvocation() {
        assertThrows(AccessDeniedException.class, dashboardService::getDashboard);
        verify(userRepository, org.mockito.Mockito.never()).countByRole(org.mockito.ArgumentMatchers.any());
        verify(tagRepository, org.mockito.Mockito.never()).count();
        verify(repository, org.mockito.Mockito.never()).countByActiveTrue();
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean IRubricRepository repository() { return mock(IRubricRepository.class); }
        @Bean AdminRubricService service(IRubricRepository repository) { return new AdminRubricServiceImpl(repository); }
        @Bean IUserRepository userRepository() { return mock(IUserRepository.class); }
        @Bean ITagRepository tagRepository() { return mock(ITagRepository.class); }
        @Bean AdminUserService userService(IUserRepository repository) { return new AdminUserServiceImpl(repository); }
        @Bean AdminDashboardService dashboardService(IUserRepository users, ITagRepository tags,
                                                      IRubricRepository rubrics) {
            return new AdminDashboardServiceImpl(users, tags, rubrics);
        }
    }
}
