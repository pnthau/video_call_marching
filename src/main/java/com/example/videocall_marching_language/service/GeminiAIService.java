package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.SpeechEvaluationDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiAIService {

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.url:}")
    private String geminiApiUrl;

    /**
     * Thuật toán Giai đoạn 2: Trích xuất từ khoá bằng Gemini và tự động che mờ (Masking).
     */
    public String generateMaskedScript(String originalScript) {
        // Fallback nếu không có API key
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            log.warn("GEMINI_API_KEY is empty. Using simple fallback masking.");
            return simpleFallbackMasking(originalScript);
        }

        try {
            // 1. Tạo Prompt yêu cầu Gemini trả về JSON list từ khoá
            String prompt = "Đóng vai một chuyên gia ngôn ngữ, hãy trích xuất khoảng 30% các từ khóa quan trọng nhất " +
                            "(ưu tiên động từ chính, danh từ, tính từ mang ý nghĩa cốt lõi) từ đoạn văn sau để làm bài tập điền từ. " +
                            "YÊU CẦU BẮT BUỘC: Chỉ trả về một mảng JSON chứa các từ (ví dụ: [\"word1\", \"word2\"]), không được kèm theo bất kỳ văn bản giải thích nào khác.\n\n" +
                            "Đoạn văn: " + originalScript;

            // 2. Build JSON Body cho HTTP Request
            String requestBody = """
                    {
                      "contents": [{
                        "parts":[{"text": "%s"}]
                      }]
                    }
                    """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

            // 3. Gửi HTTP POST thuần tới Gemini API
            ResponseEntity<String> response = restTemplate.postForEntity(geminiApiUrl + "?key=" + geminiApiKey, requestEntity, String.class);

            // 4. Parse JSON Response
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            String responseText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            // 5. Làm sạch kết quả trả về (đôi khi AI chèn markdown ```json ... ```)
            String jsonArrayStr = responseText.replaceAll("(?s).*?\\[", "[").replaceAll("(?s)\\].*", "]");
            JsonNode arrayNode = objectMapper.readTree(jsonArrayStr);
            
            List<String> keywordsToMask = new ArrayList<>();
            if (arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    keywordsToMask.add(node.asText().trim());
                }
            }

            // 6. Thay thế các từ khoá trong script gốc bằng "____"
            String maskedScript = originalScript;
            for (String keyword : keywordsToMask) {
                // Sử dụng Regex (?i) để không phân biệt hoa thường, và \\b để bắt đúng từ (tránh thay thế sai vào một phần của từ khác)
                String regex = "(?i)\\b" + keyword + "\\b";
                maskedScript = maskedScript.replaceAll(regex, "____");
            }

            return maskedScript;

        } catch (Exception e) {
            log.error("Error calling Gemini API for masking: ", e);
            return simpleFallbackMasking(originalScript); // Fallback nếu lỗi kết nối / parse
        }
    }

    /**
     * Fallback đơn giản khi Gemini API gặp sự cố (Che ngẫu nhiên các từ dài hơn 4 ký tự)
     */
    private String simpleFallbackMasking(String script) {
        String[] words = script.split("\\s+");
        StringBuilder masked = new StringBuilder();
        int count = 0;
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-zA-Z]", "");
            if (cleanWord.length() > 4 && count % 3 == 0) {
                masked.append("____").append(" ");
            } else {
                masked.append(word).append(" ");
            }
            count++;
        }
        return masked.toString().trim();
    }

    /**
     * Đánh giá câu nói của user so với câu gốc (Dùng chung cho cả 3 Giai đoạn).
     *
     * @param originalSentence Câu gốc trong script (câu chuẩn)
     * @param userSentence     Câu mà user đã nói (sau khi STT chuyển thành text)
     * @param phase            Giai đoạn hiện tại (1, 2, 3) để AI điều chỉnh mức độ nghiêm khắc
     * @return SpeechEvaluationDTO chứa kết quả đánh giá
     */
    public SpeechEvaluationDTO evaluateSpeech(String originalSentence, String userSentence, int phase) {
        // Fallback mock khi không có API key
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            log.warn("GEMINI_API_KEY is empty. Using mock evaluation.");
            return mockEvaluation(originalSentence, userSentence);
        }

        try {
            String phaseContext = switch (phase) {
                case 1 -> "Người dùng đang ở Giai đoạn 1 (Nghe & Lặp lại từng câu). Hãy đánh giá chặt chẽ về phát âm và ngữ pháp.";
                case 2 -> "Người dùng đang ở Giai đoạn 2 (Điền từ khuyết). Hãy đánh giá xem người dùng có nhớ đúng từ khóa và phát âm chính xác không.";
                case 3 -> "Người dùng đang ở Giai đoạn 3 (Nói tự do). Hãy đánh giá tổng thể về nội dung, ngữ pháp, và sự lưu loát.";
                default -> "Hãy đánh giá câu nói của người dùng.";
            };

            String prompt = "Bạn là một giáo viên ngôn ngữ chuyên nghiệp. " + phaseContext + "\n\n" +
                    "Câu gốc (chuẩn): \"" + originalSentence + "\"\n" +
                    "Câu người dùng nói: \"" + userSentence + "\"\n\n" +
                    "YÊU CẦU BẮT BUỘC: Chỉ trả về JSON duy nhất với cấu trúc sau, KHÔNG kèm văn bản giải thích:\n" +
                    "{\n" +
                    "  \"isCorrect\": true/false,\n" +
                    "  \"correctedSentence\": \"câu đã sửa đúng (nếu sai, nếu đúng thì giữ nguyên câu gốc)\",\n" +
                    "  \"errors\": [\"mô tả lỗi 1\", \"mô tả lỗi 2\"],\n" +
                    "  \"suggestion\": \"lời khuyên ngắn gọn để cải thiện\"\n" +
                    "}";

            String responseText = callGemini(prompt);

            // Parse JSON response từ Gemini
            String jsonStr = responseText.replaceAll("(?s).*?\\{", "{").replaceAll("(?s)}[^}]*$", "}");
            JsonNode resultNode = objectMapper.readTree(jsonStr);

            List<String> errors = new ArrayList<>();
            JsonNode errorsNode = resultNode.path("errors");
            if (errorsNode.isArray()) {
                for (JsonNode e : errorsNode) {
                    errors.add(e.asText());
                }
            }

            return SpeechEvaluationDTO.builder()
                    .isCorrect(resultNode.path("isCorrect").asBoolean(false))
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(resultNode.path("correctedSentence").asText(originalSentence))
                    .errors(errors)
                    .suggestion(resultNode.path("suggestion").asText(""))
                    .build();

        } catch (Exception e) {
            log.error("Error calling Gemini API for evaluation: ", e);
            return mockEvaluation(originalSentence, userSentence);
        }
    }

    /**
     * Gọi Gemini API dùng chung (tái sử dụng cho cả masking và evaluation).
     */
    private String callGemini(String prompt) throws Exception {
        String requestBody = """
                {
                  "contents": [{
                    "parts":[{"text": "%s"}]
                  }]
                }
                """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                geminiApiUrl + "?key=" + geminiApiKey, requestEntity, String.class);

        JsonNode rootNode = objectMapper.readTree(response.getBody());
        return rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
    }

    /**
     * Mock evaluation khi không có API Key hoặc lỗi kết nối.
     * So sánh đơn giản bằng cách kiểm tra chuỗi ký tự giống nhau.
     */
    private SpeechEvaluationDTO mockEvaluation(String originalSentence, String userSentence) {
        boolean isCorrect = originalSentence.trim().equalsIgnoreCase(userSentence.trim());

        List<String> errors = new ArrayList<>();
        if (!isCorrect) {
            errors.add("Câu bạn nói chưa khớp với câu gốc. (Mock - không có Gemini API Key)");
        }

        return SpeechEvaluationDTO.builder()
                .isCorrect(isCorrect)
                .originalSentence(originalSentence)
                .userSentence(userSentence)
                .correctedSentence(isCorrect ? originalSentence : originalSentence)
                .errors(errors)
                .suggestion(isCorrect ? "Tuyệt vời! Bạn đã nói đúng." : "Hãy thử lại, đọc chậm và rõ ràng hơn.")
                .build();
    }
}
