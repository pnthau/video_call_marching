package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.repository.ITagCategoryRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataInitializerRubricTests {
    @Test
    void firstRunSeedsExactlySevenAndSecondRunIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run();
        assertEquals(7, fixture.store.size());
        assertEquals(7, RubricCriteria.values().length);
        fixture.run();
        assertEquals(7, fixture.store.size());
        verify(fixture.rubrics, times(7)).save(any(Rubric.class));
    }

    @Test
    void partialSetIsBackfilledWithoutOverwritingCustomization() throws Exception {
        Fixture fixture = new Fixture();
        Rubric customized = Rubric.builder().criteria(RubricCriteria.ACCURACY)
                .displayName("Custom").description("Custom description").active(false).build();
        fixture.store.put(RubricCriteria.ACCURACY, customized);
        fixture.run();
        assertEquals(7, fixture.store.size());
        assertSame(customized, fixture.store.get(RubricCriteria.ACCURACY));
        assertEquals("Custom", customized.getDisplayName());
        assertEquals("Custom description", customized.getDescription());
        assertFalse(customized.isActive());
        verify(fixture.rubrics, times(6)).save(any(Rubric.class));

        fixture.run();
        assertEquals(7, fixture.store.size());
        assertEquals("Custom", customized.getDisplayName());
        assertEquals("Custom description", customized.getDescription());
        assertFalse(customized.isActive());
        verify(fixture.rubrics, times(6)).save(any(Rubric.class));
    }

    @Test
    void unknownCriteriaFailsStartupWithoutDeletingData() {
        Fixture fixture = new Fixture();
        fixture.rawCriteria = List.of("ACCURACY", "SUPPORTIVENESS");

        IllegalStateException exception = assertThrows(IllegalStateException.class, fixture::run);

        assertTrue(exception.getMessage().contains("SUPPORTIVENESS"));
        verify(fixture.rubrics, never()).save(any(Rubric.class));
        verify(fixture.rubrics, never()).delete(any(Rubric.class));
    }

    private static class Fixture {
        final ITagCategoryRepository categories = mock(ITagCategoryRepository.class);
        final ITagRepository tags = mock(ITagRepository.class);
        final IRubricRepository rubrics = mock(IRubricRepository.class);
        final Map<RubricCriteria, Rubric> store = new EnumMap<>(RubricCriteria.class);
        final CommandLineRunner runner;
        List<String> rawCriteria;

        Fixture() {
            when(categories.count()).thenReturn(1L);
            when(rubrics.findByCriteria(any())).thenAnswer(invocation ->
                    Optional.ofNullable(store.get(invocation.getArgument(0))));
            when(rubrics.findAllCriteriaCodes()).thenAnswer(invocation -> rawCriteria != null
                    ? rawCriteria
                    : store.keySet().stream().map(Enum::name).toList());
            when(rubrics.save(any())).thenAnswer(invocation -> {
                Rubric rubric = invocation.getArgument(0);
                store.put(rubric.getCriteria(), rubric);
                return rubric;
            });
            runner = new DataInitializer().initData(categories, tags, rubrics);
        }

        void run() throws Exception { runner.run(); }
    }
}
