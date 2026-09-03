package com.example.videocall_marching_language.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Chuyển đổi văn bản thành âm thanh qua Python Edge-TTS microservice
     * @param text Văn bản cần đọc
     * @param language Ngôn ngữ ("ja" hoặc "en")
     * @return Chuỗi Base64 của file âm thanh
     */
    public String generateAudioBase64(String text, String language) {
        String url = "http://127.0.0.1:5000/tts";
        
        try {
            log.info("Đang gọi Edge-TTS Python server...");
            String rawResponse = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", text, "language", language))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode rootNode = objectMapper.readTree(rawResponse);
            if ("success".equals(rootNode.path("status").asText())) {
                return rootNode.path("audio_base64").asText();
            } else {
                log.error("Lỗi từ TTS server: {}", rootNode.path("message").asText());
                return null;
            }
        } catch (Exception e) {
            log.error("Lỗi kết nối tới TTS server: {}", e.getMessage());
            return null;
        }
    }
}
