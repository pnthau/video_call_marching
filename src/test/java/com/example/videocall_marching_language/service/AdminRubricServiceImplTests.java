package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.admin.RubricUpdateForm;
import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.exception.RubricNotFoundException;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.service.impl.AdminRubricServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminRubricServiceImplTests {
    private final IRubricRepository repository = mock(IRubricRepository.class);
    private final AdminRubricServiceImpl service = new AdminRubricServiceImpl(repository);

    @Test
    void updateOnlyChangesEditableTextAndKeepsStableCriteria() {
        Rubric rubric = rubric(true);
        when(repository.findById(1L)).thenReturn(Optional.of(rubric));
        when(repository.save(rubric)).thenReturn(rubric);

        var response = service.update(1L, new RubricUpdateForm("  Tên mới  ", "  Mô tả mới  "));

        assertEquals("Tên mới", response.displayName());
        assertEquals("Mô tả mới", response.description());
        assertEquals(RubricCriteria.ACCURACY, response.criteria());
        assertTrue(response.active());
    }

    @Test
    void toggleChangesOnlyActiveFlag() {
        Rubric rubric = rubric(true);
        when(repository.findById(1L)).thenReturn(Optional.of(rubric));
        when(repository.save(rubric)).thenReturn(rubric);
        assertFalse(service.toggleActive(1L).active());
        assertEquals(RubricCriteria.ACCURACY, rubric.getCriteria());
    }

    @Test
    void missingRubricThrows() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(RubricNotFoundException.class, () -> service.findById(99L));
        assertThrows(RubricNotFoundException.class, () -> service.toggleActive(99L));
    }

    private Rubric rubric(boolean active) {
        return Rubric.builder().id(1L).criteria(RubricCriteria.ACCURACY).displayName("Cũ")
                .description("Cũ").active(active).build();
    }
}
