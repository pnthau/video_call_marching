package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.SentenceExercise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ISentenceExerciseRepository extends JpaRepository<SentenceExercise, Long> {
    List<SentenceExercise> findByLessonSentenceId(Long sentenceId);
}
