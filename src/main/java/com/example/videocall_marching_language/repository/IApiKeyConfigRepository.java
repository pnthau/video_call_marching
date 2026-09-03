package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.ApiKeyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IApiKeyConfigRepository extends JpaRepository<ApiKeyConfig, Long> {
    Optional<ApiKeyConfig> findByUserIdAndProviderAndIsActiveTrue(Long userId, String provider);

    List<ApiKeyConfig> findByUserId(Long userId);
}
