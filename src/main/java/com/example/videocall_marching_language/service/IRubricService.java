package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.Rubric;

import java.util.List;
import java.util.Optional;

public interface IRubricService {

    /** Lấy tất cả rubric, sắp xếp theo id */
    List<Rubric> findAll();

    /** Lấy rubric đang active — dùng cho rating form */
    List<Rubric> findAllActive();

    Optional<Rubric> findById(Long id);

    Rubric save(Rubric rubric);

    /**
     * Bật/tắt isActive của rubric.
     * Không có delete — rubric phải giữ lại để lịch sử rating không bị mất tham chiếu.
     */
    void toggleActive(Long id);
}
