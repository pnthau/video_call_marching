package com.example.videocall_marching_language.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "current_level")
    private int currentLevel;

    @Column(name = "trust_score")
    private float trustScore;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "is_phone_verified")
    private Boolean isPhoneVerified;

}
