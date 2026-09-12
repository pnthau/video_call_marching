package com.example.videocall_marching_language.repository;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.enums.TagCategoryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ITagRepository extends JpaRepository<Tag, Long> {
    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c " +
            "WHERE c.active = true ORDER BY c.displayOrder ASC, t.name ASC")
    List<Tag> findAllForActiveCategories();

    @Query("SELECT t FROM Tag t JOIN FETCH t.tagCategory c WHERE t.id = :id AND c.active = true")
    Optional<Tag> findSelectableById(Long id);
    @Query(value = "SELECT t FROM Tag t JOIN FETCH t.tagCategory c " +
            "WHERE (:name = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :name, '%'))) " +
            "AND (:type IS NULL OR c.type = :type) " +
            "ORDER BY t.id DESC",
            countQuery = "SELECT COUNT(t) FROM Tag t JOIN t.tagCategory c " +
                    "WHERE (:name = '' OR LOWER(t.name) LIKE LOWER(CONCAT('%', :name, '%'))) " +
                    "AND (:type IS NULL OR c.type = :type)")
    Page<Tag> searchForAdmin(@Param("name") String name,
                             @Param("type") TagCategoryType type,
                             Pageable pageable);

    boolean existsByTagCategoryId(Long categoryId);
}
