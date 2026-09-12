package com.example.videocall_marching_language.service.ai;

import com.example.videocall_marching_language.enums.AIProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GroqAiServiceImpl implements IAIService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Groq API endpoint (tương thích chuẩn OpenAI)
    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String groqApiUrl;

    // Model Qwen mặc định trên Groq
    @Value("${groq.model:qwen/qwen3.8-27b}")
    private String defaultModel;

    // Danh sách các model Qwen dự phòng trên Groq
    private static final List<String> FALLBACK_QWEN_MODELS = List.of(
            "qwen/qwen3.8-27b",
            "qwen/qwen3.6-27b",
            "qwen-qwq-32b",
            "llama-3.3-70b-versatile"
    );

    public GroqAiServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public AIProvider getProvider() {
        return AIProvider.GROQ;
    }

    @Override
    public String generateResponse(String prompt, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Groq API Key không được để trống.");
        }

        LinkedHashSet<String> modelsToTry = new LinkedHashSet<>();
        if (defaultModel != null && !defaultModel.isBlank()) {
            modelsToTry.add(defaultModel.trim());
        }
        modelsToTry.addAll(FALLBACK_QWEN_MODELS);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        Exception lastException = null;

        for (String modelName : modelsToTry) {
            try {
                log.info("===> [GROQ QWEN API REQUEST] Model: {}, prompt preview: {}",
                        modelName, prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt);

                Map<String, Object> message = Map.of(
                        "role", "user",
                        "content", prompt
                );

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", modelName);
                requestBody.put("messages", List.of(message));
                requestBody.put("temperature", 0.3);
                // Bật response_format json_object để model Qwen luôn trả về JSON sạch
                requestBody.put("response_format", Map.of("type", "json_object"));

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                long startTime = System.currentTimeMillis();
                ResponseEntity<String> response = restTemplate.postForEntity(groqApiUrl, entity, String.class);
                long duration = System.currentTimeMillis() - startTime;

                log.info("<=== [GROQ QWEN RESPONSE] Model '{}' phản hồi thành công trong {}ms", modelName, duration);

                return extractContentFromResponse(response.getBody());

            } catch (HttpStatusCodeException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();

                log.warn("⚠️ [GROQ HTTP {}] Model '{}' gặp lỗi: {}. Thử model tiếp theo...",
                        statusCode, modelName, errorBody.length() > 150 ? errorBody.substring(0, 150) : errorBody);

                // Nếu lỗi 400 do không hỗ trợ response_format, thử lại ngay không có response_format
                if (statusCode == 400 && errorBody.contains("response_format")) {
                    try {
                        return retryWithoutResponseFormat(modelName, prompt, headers);
                    } catch (Exception ex) {
                        lastException = ex;
                    }
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("⚠️ [GROQ ERROR] Model '{}' gặp ngoại lệ: {}. Thử model tiếp theo...", modelName, e.getMessage());
            }
        }

        throw new RuntimeException("Lỗi giao tiếp với Groq API: Không thể gọi bất kỳ model Qwen nào thành công. " +
                (lastException != null ? "Chi tiết: " + lastException.getMessage() : ""));
    }

    private String retryWithoutResponseFormat(String modelName, String prompt, HttpHeaders headers) {
        log.info("===> [GROQ RETRY] Thử lại model {} không kèm response_format...", modelName);
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", prompt
        );
        Map<String, Object> requestBody = Map.of(
                "model", modelName,
                "messages", List.of(message),
                "temperature", 0.3
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(groqApiUrl, entity, String.class);
        return extractContentFromResponse(response.getBody());
    }

    private String extractContentFromResponse(String responseBody) {
        try {
            JsonNode rootNode = objectMapper.readTree(responseBody);
            if (rootNode.has("choices") && rootNode.get("choices").isArray() && !rootNode.get("choices").isEmpty()) {
                JsonNode messageNode = rootNode.get("choices").get(0).path("message");
                String content = messageNode.path("content").asText("");

                // Loại bỏ thẻ suy nghĩ <think>...</think> nếu model có reasoning mode
                if (content.contains("</think>")) {
                    content = content.substring(content.lastIndexOf("</think>") + "</think>".length()).trim();
                }

                log.info("<=== [GROQ RAW EXTRACTED]:\n{}", content);
                return content;
            }
        } catch (Exception e) {
            log.warn("Lỗi parse JSON response từ Groq: {}", e.getMessage());
        }
        return responseBody;
    }
}
