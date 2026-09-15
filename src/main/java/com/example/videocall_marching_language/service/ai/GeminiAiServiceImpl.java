package com.example.videocall_marching_language.service.ai;

import com.example.videocall_marching_language.enums.AIProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiAiServiceImpl implements IAIService {
    private final RestTemplate restTemplate;
    
    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}")
    private String geminiApiUrl;

    public GeminiAiServiceImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public AIProvider getProvider() {
        return AIProvider.GEMINI;
    }

    @Override
    public String generateResponse(String prompt, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Dùng x-goog-api-key qua header
        headers.set("x-goog-api-key", apiKey);

        HttpEntity<Map<String, Object>> entity = getMapHttpEntity(prompt, headers);

        try {
            log.info("===> [GEMINI API REQUEST] Url: {}, prompt preview: {}", geminiApiUrl, 
                    prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt);
            ResponseEntity<String> response = restTemplate.postForEntity(geminiApiUrl, entity, String.class);
            
            // Lấy text từ JSON response của Gemini
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response.getBody());
            
            if (rootNode.has("candidates") && rootNode.get("candidates").isArray() && !rootNode.get("candidates").isEmpty()) {
                JsonNode contentNode = rootNode.get("candidates").get(0).path("content").path("parts");
                if (contentNode.isArray() && !contentNode.isEmpty()) {
                    String extractedText = contentNode.get(0).path("text").asText("");
                    log.info("<=== [GEMINI API RAW RESPONSE EXTRACTED]:\n{}", extractedText);
                    return extractedText;
                }
            }
            
            log.warn("<=== [GEMINI CANDIDATES EMPTY] Returning entire body: {}", response.getBody());
            return response.getBody();

        } catch (HttpStatusCodeException e) {
            String errorBody = e.getResponseBodyAsString();
            log.error("Lỗi HTTP {} từ Gemini API: {}", e.getStatusCode(), errorBody);
            throw new RuntimeException("Lỗi Gemini API (" + e.getStatusCode() + "): " + errorBody, e);
        } catch (Exception e) {
            log.error("Lỗi giao tiếp với Gemini API: {}", e.getMessage(), e);
            throw new RuntimeException("Lỗi giao tiếp với Gemini API: " + e.getMessage(), e);
        }
    }

    private static @NonNull HttpEntity<Map<String, Object>> getMapHttpEntity(String prompt, HttpHeaders headers) {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));

        Map<String, Object> thinkingConfig = Map.of(
                "thinkingBudget", 0
        );

        Map<String, Object> generationConfig = Map.of(
            "response_mime_type", "application/json",
                "thinkingConfig", thinkingConfig
        );

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
                "generationConfig", generationConfig
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        return entity;
    }
}
