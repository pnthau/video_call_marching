package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.LessonSentence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ILessonSentenceRepository extends JpaRepository<LessonSentence, Long> {
    List<LessonSentence> findByAudioLessonIdOrderBySentenceIndexAsc(Long audioLessonId);
}
