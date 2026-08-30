package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ITagRepository extends JpaRepository<Tag, Long> {
    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c " +
            "WHERE c.active = true ORDER BY c.displayOrder ASC, t.name ASC")
    List<Tag> findAllForActiveCategories();

    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c WHERE t.id = :id AND c.active = true")
    Optional<Tag> findSelectableById(Long id);
}
