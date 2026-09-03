package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.controller.admin.AdminController;
import com.example.videocall_marching_language.dto.admin.DashboardResponse;
import com.example.videocall_marching_language.dto.admin.RubricResponse;
import com.example.videocall_marching_language.dto.admin.RubricUpdateForm;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.service.AdminDashboardService;
import com.example.videocall_marching_language.service.AdminRubricService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTests {

    private MockMvc mockMvc;

    @Mock
    private AdminDashboardService dashboardService;

    @Mock
    private AdminRubricService rubricService;

    @BeforeEach
    void setUp() {
        reset(dashboardService, rubricService);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(dashboardService, rubricService)).build();
    }

    @Test
    @DisplayName("GET /admin & GET /admin/rubrics - Trả về view dashboard và danh sách rubric")
    void dashboardAndListsUseServiceDtos() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(new DashboardResponse(10L, 5L, 3L));
        when(rubricService.findAll()).thenReturn(List.of(
                new RubricResponse(1L, RubricCriteria.ACCURACY, "Độ chính xác", "Mô tả", true)
        ));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeExists("dashboard"));

        mockMvc.perform(get("/admin/rubrics"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/rubrics/list"))
                .andExpect(model().attributeExists("rubrics"));

        verify(dashboardService).getDashboard();
        verify(rubricService).findAll();
    }

    @Test
    @DisplayName("GET & POST /admin/rubrics/edit/{id} - Hiển thị form và cập nhật thông tin Rubric")
    void editRubricFormAndSubmit() throws Exception {
        RubricResponse response = new RubricResponse(1L, RubricCriteria.ACCURACY, "Độ chính xác", "Mô tả", true);
        when(rubricService.findById(1L)).thenReturn(response);

        mockMvc.perform(get("/admin/rubrics/edit/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/rubrics/edit"))
                .andExpect(model().attributeExists("rubric", "form"));

        mockMvc.perform(post("/admin/rubrics/edit/1")
                        .param("displayName", "Độ chính xác nâng cao")
                        .param("description", "Mô tả mới"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rubrics?updated"));

        verify(rubricService).update(eq(1L), any(RubricUpdateForm.class));
    }

    @Test
    @DisplayName("POST /admin/rubrics/toggle/{id} - Bật/Tắt trạng thái Rubric")
    void toggleRubricStatus() throws Exception {
        mockMvc.perform(post("/admin/rubrics/toggle/1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/rubrics?toggled"));

        verify(rubricService).toggleActive(1L);
    }
}