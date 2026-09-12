package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.SentenceGrammar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ISentenceGrammarRepository extends JpaRepository<SentenceGrammar, Long> {
    Optional<SentenceGrammar> findByLessonSentenceId(Long sentenceId);
}
