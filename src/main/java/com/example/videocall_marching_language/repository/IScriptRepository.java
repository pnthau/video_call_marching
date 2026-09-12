package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.dto.script.IScriptSummaryView;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.Script;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IScriptRepository extends JpaRepository<Script, Long> {
    // Lấy script theo 1 chủ đề (theo ID)
    List<Script> findByTagId(Long tagId);

    // Lấy script theo nhiều tên chủ đề (vd: tags=n5,combini)
    List<IScriptSummaryView> findByTagNameIn(List<String> tagNames);

    @Query("SELECT new com.example.videocall_marching_language.dto.script.TopicWithCountDTO(" +
           "s.tag.id, s.tag.name, LOWER(s.language), LOWER(s.level), COUNT(s.id)) " +
           "FROM Script s " +
           "WHERE s.tag IS NOT NULL " +
           "GROUP BY s.tag.id, s.tag.name, LOWER(s.language), LOWER(s.level) " +
           "ORDER BY s.tag.name ASC")
    List<TopicWithCountDTO> findTopicsWithScriptCount();

    @Query("SELECT DISTINCT s FROM Script s JOIN FETCH s.tag t " +
           "WHERE (:tagId IS NULL OR t.id = :tagId) " +
           "AND (:tagName IS NULL OR LOWER(t.name) = LOWER(:tagName)) " +
           "AND (:hasTagNames = false OR t.name IN :tagNames) " +
           "AND (:language IS NULL OR :language = '' OR LOWER(s.language) = LOWER(:language)) " +
           "AND (:level IS NULL OR :level = '' OR :level = 'all' OR LOWER(s.level) = LOWER(:level)) " +
           "AND (:minDuration IS NULL OR s.targetDuration >= :minDuration) " +
           "AND (:maxDuration IS NULL OR s.targetDuration <= :maxDuration) " +
           "ORDER BY s.id ASC")
    List<Script> findByCriteria(
            @Param("tagId") Long tagId,
            @Param("tagName") String tagName,
            @Param("hasTagNames") boolean hasTagNames,
            @Param("tagNames") List<String> tagNames,
            @Param("language") String language,
            @Param("level") String level,
            @Param("minDuration") Integer minDuration,
            @Param("maxDuration") Integer maxDuration
    );

    @Query("SELECT DISTINCT LOWER(s.language) FROM Script s WHERE s.language IS NOT NULL AND TRIM(s.language) <> ''")
    List<String> findDistinctLanguages();
}
