package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.AudioLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IAudioLessonRepository extends JpaRepository<AudioLesson, Long> {

    List<AudioLesson> findByTagIdOrderByCreatedAtDesc(Long tagId);

    @Query("SELECT al FROM AudioLesson al LEFT JOIN FETCH al.tag ORDER BY al.createdAt DESC")
    List<AudioLesson> findAllWithTag();

    @Query("SELECT new com.example.videocall_marching_language.dto.script.TopicWithCountDTO(" +
           "al.tag.id, al.tag.name, LOWER(al.language), LOWER(al.level), COUNT(al.id)) " +
           "FROM AudioLesson al " +
           "WHERE al.tag IS NOT NULL " +
           "GROUP BY al.tag.id, al.tag.name, LOWER(al.language), LOWER(al.level) " +
           "ORDER BY al.tag.name ASC")
    List<TopicWithCountDTO> findTopicsWithAudioLessonCount();

    @Query("SELECT DISTINCT al FROM AudioLesson al LEFT JOIN FETCH al.tag t " +
           "WHERE (:tagId IS NULL OR t.id = :tagId) " +
           "AND (:language IS NULL OR :language = '' OR LOWER(al.language) = LOWER(:language)) " +
           "AND (:level IS NULL OR :level = '' OR :level = 'all' OR LOWER(al.level) = LOWER(:level)) " +
           "ORDER BY al.createdAt DESC")
    List<AudioLesson> findByCriteria(
            @Param("tagId") Long tagId,
            @Param("language") String language,
            @Param("level") String level
    );

    @Query("SELECT DISTINCT LOWER(al.language) FROM AudioLesson al WHERE al.language IS NOT NULL AND TRIM(al.language) <> ''")
    List<String> findDistinctLanguages();
}
