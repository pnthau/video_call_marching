package com.example.videocall_marching_language.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "social_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_social_account_provider_id", columnNames = {"provider", "provider_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_id", nullable = false)
    private String providerId;
}
