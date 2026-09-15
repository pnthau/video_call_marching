package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.UserAiSetting;
import com.example.videocall_marching_language.enums.AIProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IUserAiSettingRepository extends JpaRepository<UserAiSetting, Long> {

    // Tìm API Key cụ thể của một Provider (dùng lúc xài AI hoặc lúc Update)
    Optional<UserAiSetting> findByUserIdAndAiProvider(Long userId, AIProvider aiProvider);

    // Lấy danh sách tất cả các Key user đã lưu (dùng để hiển thị ra màn hình Profile)
    List<UserAiSetting> findByUserId(Long userId);
}
