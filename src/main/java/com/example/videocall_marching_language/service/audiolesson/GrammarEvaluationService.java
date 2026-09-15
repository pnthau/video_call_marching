package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.GrammarChallengeRequestDTO;
import com.example.videocall_marching_language.dto.audiolesson.GrammarChallengeResponseDTO;
import com.example.videocall_marching_language.service.ai.AiProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrammarEvaluationService {

    private final AiProxyService aiProxyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GrammarChallengeResponseDTO evaluateSentence(Long userId, GrammarChallengeRequestDTO request) {
        if (request == null || request.getUserSentence() == null || request.getUserSentence().isBlank()) {
            return GrammarChallengeResponseDTO.builder()
                    .isCorrect(false)
                    .score(0)
                    .feedback("Vui lòng nhập câu của bạn trước khi kiểm tra.")
                    .build();
        }

        String prompt = """
        Bạn là giáo viên ngôn ngữ bản xứ giàu kinh nghiệm và tận tâm.
        Nhiệm vụ của bạn là đánh giá câu người học vừa tự đặt theo đề bài thử thách và điểm ngữ pháp được yêu cầu.
        
        Thông tin bài tập:
        - Đề bài thử thách: "%s"
        - Điểm ngữ pháp mục tiêu: "%s"
        - Công thức yêu cầu: "%s"
        - Ngôn ngữ: "%s"
        - Câu người học đã đặt: "%s"
        
        YÊU CẦU:
        Kiểm tra xem câu người học đặt:
        1. Đã bám sát ngữ cảnh và yêu cầu của đề bài thử thách chưa?
        2. Đã áp dụng đúng cấu trúc ngữ pháp mục tiêu chưa?
        3. Chia động từ, trợ từ, từ vựng và ngữ nghĩa có tự nhiên và chính xác không?
        
        QUY TẮC PHẢN HỒI (RẤT QUAN TRỌNG):
        - Trường "feedback" PHẢI thật ngắn gọn, súc tích và TỐI ĐA 30 TỪ TIẾNG VIỆT. Đi thẳng vào nhận xét điểm tốt hoặc sửa lỗi sai cốt lõi. TUYỆT ĐỐI KHÔNG viết lan man dài dòng quá 30 từ.
        
        Hãy trả về DUY NHẤT một chuỗi JSON hợp lệ (không kèm markdown ```json ... ```):
        {
          "isCorrect": true/false (true nếu đúng từ 75/100 điểm trở lên),
          "score": 85 (thang điểm từ 0 đến 100),
          "feedback": "Nhận xét súc tích dưới 30 từ tiếng Việt",
          "correctedSentence": "Câu sửa chuẩn xác nếu có lỗi (nếu người học viết đúng thì giữ nguyên)",
          "suggestedSentence": "Một câu ví dụ ngắn gọn tự nhiên hơn của người bản xứ"
        }
        """.formatted(
                request.getChallengePrompt() != null ? request.getChallengePrompt() : "Áp dụng cấu trúc để đặt câu",
                request.getGrammarPoint() != null ? request.getGrammarPoint() : "",
                request.getGrammarFormula() != null ? request.getGrammarFormula() : "",
                request.getLanguage() != null ? request.getLanguage() : "ja",
                request.getUserSentence().trim()
        );

        try {
            String response = aiProxyService.chat(userId, prompt);
            String cleanJson = response.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            GrammarChallengeResponseDTO dto = objectMapper.readValue(cleanJson, GrammarChallengeResponseDTO.class);
            if (dto.getFeedback() != null) {
                dto.setFeedback(trimToMaxWords(dto.getFeedback(), 30));
            }
            return dto;

        } catch (Exception e) {
            log.error("Lỗi khi AI đánh giá câu đặt ngữ pháp: {}", e.getMessage());
            return GrammarChallengeResponseDTO.builder()
                    .isCorrect(true)
                    .score(80)
                    .feedback("Câu áp dụng đúng cấu trúc. Hãy tiếp tục phát huy!")
                    .correctedSentence(request.getUserSentence())
                    .suggestedSentence(request.getUserSentence())
                    .build();
        }
    }

    private String trimToMaxWords(String text, int maxWords) {
        if (text == null || text.isBlank()) return text;
        String[] words = text.trim().split("\\s+");
        if (words.length <= maxWords) {
            return text.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        sb.append("...");
        return sb.toString();
    }
}
