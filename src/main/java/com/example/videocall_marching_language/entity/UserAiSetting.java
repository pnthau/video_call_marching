package com.example.videocall_marching_language.entity;

import com.example.videocall_marching_language.enums.AIProvider;
import com.example.videocall_marching_language.utils.StringCryptoConverter;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_ai_settings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "ai_provider"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAiSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_provider", length = 20, nullable = false)
    private AIProvider aiProvider;

    @Convert(converter = StringCryptoConverter.class)
    @Column(name = "ai_api_key", columnDefinition = "TEXT", nullable = false)
    private String aiApiKey;
}