package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.controller.admin.AdminController;
import com.example.videocall_marching_language.dto.admin.*;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.exception.AdminUsernameValidationException;
import com.example.videocall_marching_language.service.AdminDashboardService;
import com.example.videocall_marching_language.service.AdminRubricService;
import com.example.videocall_marching_language.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminControllerTests {
    private final AdminDashboardService dashboard = mock(AdminDashboardService.class);
    private final AdminRubricService rubrics = mock(AdminRubricService.class);
    private final AdminUserService users = mock(AdminUserService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(dashboard, rubrics, users);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(dashboard, rubrics, users)).build();
    }

    @Test
    void dashboardAndListsUseServiceDtos() throws Exception {
        when(dashboard.getDashboard()).thenReturn(new DashboardResponse(2, 3, 7));
        when(rubrics.findAll()).thenReturn(List.of());
        when(users.search(anyString(), any())).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(view().name("admin/dashboard"));
        mockMvc.perform(get("/admin/rubrics")).andExpect(status().isOk()).andExpect(view().name("admin/rubrics/list"));
        mockMvc.perform(get("/admin/users").param("search", "ana")).andExpect(status().isOk()).andExpect(view().name("admin/users/list"));
    }

    @Test
    void rubricGetPostToggleAndValidation() throws Exception {
        var rubric = new RubricResponse(1L, RubricCriteria.ACCURACY, "Accuracy", "Description", true);
        when(rubrics.findById(1L)).thenReturn(rubric);
        mockMvc.perform(get("/admin/rubrics/edit/1")).andExpect(status().isOk()).andExpect(view().name("admin/rubrics/edit"));
        mockMvc.perform(post("/admin/rubrics/edit/1").param("displayName", "New").param("description", "Desc"))
                .andExpect(status().is3xxRedirection());
        verify(rubrics).update(eq(1L), any());
        mockMvc.perform(post("/admin/rubrics/toggle/1")).andExpect(status().is3xxRedirection());
        verify(rubrics).toggleActive(1L);

        reset(rubrics);
        when(rubrics.findById(1L)).thenReturn(rubric);
        mockMvc.perform(post("/admin/rubrics/edit/1").param("displayName", " "))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "displayName"));
        verify(rubrics, never()).update(anyLong(), any());
        mockMvc.perform(post("/admin/rubrics/edit/1").param("displayName", "x".repeat(101)))
                .andExpect(model().attributeHasFieldErrors("form", "displayName"));
        mockMvc.perform(post("/admin/rubrics/edit/1").param("displayName", "ok").param("description", "x".repeat(1001)))
                .andExpect(model().attributeHasFieldErrors("form", "description"));
    }

    @Test
    void legacyRubricRoutesAreNotExposed() throws Exception {
        mockMvc.perform(get("/admin/rubrics/1/edit")).andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/rubrics/1/edit").param("displayName", "Legacy"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/rubrics/1/toggle")).andExpect(status().isNotFound());

        verifyNoInteractions(rubrics);
    }

    @Test
    void userViewEditAndAllowlistRoutes() throws Exception {
        var user = new AdminUserResponse(2L, "learner", "learner@example.com", JapaneseLevel.N4);
        when(users.findById(2L)).thenReturn(user);
        mockMvc.perform(get("/admin/users/2")).andExpect(status().isOk()).andExpect(view().name("admin/users/view"));
        mockMvc.perform(get("/admin/users/2/edit")).andExpect(status().isOk()).andExpect(view().name("admin/users/edit"));
        mockMvc.perform(post("/admin/users/2/edit").param("username", "changed")
                        .param("email", "changed@example.com").param("currentLevel", "N3")
                        .param("role", "ADMIN").param("trustScore", "10")
                        .param("providerId", "attacker-controlled-provider"))
                .andExpect(status().is3xxRedirection());
        verify(users).update(eq(2L), argThat(form -> form.getUsername().equals("changed")
                && form.getCurrentLevel() == JapaneseLevel.N3));
        mockMvc.perform(post("/admin/users/add")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/admin/users/2/delete")).andExpect(status().isNotFound());
    }

    @Test
    void serviceUsernameValidationRendersFieldErrorWithoutUpdatingAgain() throws Exception {
        var user = new AdminUserResponse(2L, "learner", "learner@example.com", JapaneseLevel.N4);
        when(users.findById(2L)).thenReturn(user);
        when(users.update(eq(2L), any()))
                .thenThrow(new AdminUsernameValidationException("Username is already in use"));

        mockMvc.perform(post("/admin/users/2/edit")
                        .param("username", "duplicate-user")
                        .param("currentLevel", "N3"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users/edit"))
                .andExpect(model().attributeHasFieldErrors("form", "username"));

        verify(users, times(1)).update(eq(2L), any());
    }
}
