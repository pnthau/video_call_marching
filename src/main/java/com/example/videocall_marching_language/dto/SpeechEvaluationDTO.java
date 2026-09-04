package com.example.videocall_marching_language.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpeechEvaluationDTO {
    // Câu nói của user đúng hay sai
    private boolean isCorrect;

    // Câu gốc (script chuẩn)
    private String originalSentence;

    // Câu user đã nói (từ STT)
    private String userSentence;

    // Câu đã được AI sửa lại cho đúng (nếu sai)
    private String correctedSentence;

    // Danh sách các lỗi cụ thể mà AI phát hiện
    private List<String> errors;

    // Gợi ý / lời khuyên từ AI để cải thiện
    private String suggestion;

    // Tỷ lệ tương đồng / độ chính xác (0.0 -> 1.0)
    private Double similarity;

    // Trạng thái đánh giá 3 cấp độ: "PASS" (Xanh lá cây), "NEAR" (Vàng - Gần đúng), "FAIL" (Đỏ - Sai quá, cần lặp lại)
    private String status;
}
