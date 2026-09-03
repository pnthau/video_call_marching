package com.example.videocall_marching_language.entity;

import com.example.videocall_marching_language.enums.RubricCriteria;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "rubrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rubric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Mã tiêu chí ổn định — không bao giờ thay đổi để không làm sai lịch sử rating.
     * Admin chỉ được sửa displayName / description / isActive.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 40)
    private RubricCriteria criteria;

    /**
     * Tên hiển thị — admin có thể sửa tự do.
     */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
