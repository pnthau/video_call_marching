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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 40, updatable = false)
    private RubricCriteria criteria;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 1000)
    private String description;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
