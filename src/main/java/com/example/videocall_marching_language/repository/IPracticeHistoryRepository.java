package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.PracticeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IPracticeHistoryRepository extends JpaRepository<PracticeHistory, Long> {
    // Lấy lịch sử luyện tập của user theo kịch bản cụ thể
    List<PracticeHistory> findByUserIdAndScriptId(Long userId, Long scriptId);
}
