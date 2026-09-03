package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.enums.RubricCriteria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IRubricRepository extends JpaRepository<Rubric, Long> {

    List<Rubric> findAllByOrderByIdAsc();

    List<Rubric> findByIsActiveTrueOrderByIdAsc();

    Optional<Rubric> findByCriteria(RubricCriteria criteria);
}
