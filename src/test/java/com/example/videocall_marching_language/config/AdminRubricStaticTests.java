package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.enums.RubricCriteria;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdminRubricStaticTests {
    @Test
    void criteriaSetIsExactlyTheSevenApprovedStableCodes() {
        assertEquals(Set.of("ACCURACY", "FLUENCY", "PRONUNCIATION_INTONATION", "STRUCTURE_LOGIC",
                        "CONTENT_INTERESTINGNESS", "BODY_LANGUAGE", "ENTHUSIASM_CONFIDENCE"),
                java.util.Arrays.stream(RubricCriteria.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void v4HasRequiredConstraintsAndDoesNotSeedData() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V4__admin_rubric_schema.sql"));
        assertTrue(sql.contains("UNIQUE (`criteria`)"));
        assertTrue(sql.contains("CHECK (`criteria` IN ("));
        for (RubricCriteria criteria : RubricCriteria.values()) {
            assertTrue(sql.contains("'" + criteria.name() + "'"));
        }
        assertTrue(sql.contains("VARCHAR(100)"));
        assertTrue(sql.contains("VARCHAR(1000)"));
        assertTrue(sql.contains("`is_active`"));
        assertTrue(sql.contains("`created_at`"));
        assertTrue(sql.contains("`updated_at`"));
        assertFalse(sql.toUpperCase().contains("INSERT INTO"));
        assertFalse(sql.contains("SUPPORTIVENESS"));
    }
}
