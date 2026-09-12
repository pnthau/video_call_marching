package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.enums.TagCategoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ITagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c " +
            "WHERE c.active = true ORDER BY c.displayOrder ASC, t.name ASC")
    List<Tag> findAllForActiveCategories();

    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c WHERE t.id = :id AND c.active = true")
    Optional<Tag> findSelectableById(@Param("id") Long id);

    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c " +
           "WHERE c.type = :type AND c.active = true " +
           "ORDER BY t.name ASC")
    List<Tag> findByTagCategoryType(@Param("type") TagCategoryType type);
}
