package com.example.videocall_marching_language.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scripts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Script {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String language;

    @Column(name = "phonetic")
    private String phoneticContent;

    @Column(name = "meaning_content", columnDefinition = "TEXT")
    private String meaningContent;

    @Column(name = "target_duration")
    private Integer targetDuration;
}
