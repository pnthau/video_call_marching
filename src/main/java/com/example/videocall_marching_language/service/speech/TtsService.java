package com.example.videocall_marching_language.service.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> ttsCache = new ConcurrentHashMap<>();

    /**
     * Chuyển đổi văn bản thành âm thanh qua Python Edge-TTS microservice
     * @param text Văn bản cần đọc
     * @param language Ngôn ngữ ("ja" hoặc "en")
     * @return Chuỗi Base64 của file âm thanh
     */
    public String generateAudioBase64(String text, String language) throws Exception {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleanText = text.trim();
        String rawLang = (language != null && !language.isBlank()) ? language.trim().toLowerCase() : "ja";
        String cleanLang = rawLang;
        if (rawLang.startsWith("vi")) cleanLang = "vi-VN";
        else if (rawLang.startsWith("ja")) cleanLang = "ja-JP";
        else if (rawLang.startsWith("en")) cleanLang = "en-US";
        else if (rawLang.startsWith("ko")) cleanLang = "ko-KR";
        else if (rawLang.startsWith("zh")) cleanLang = "zh-CN";
        else if (rawLang.startsWith("fr")) cleanLang = "fr-FR";
        else if (rawLang.startsWith("es")) cleanLang = "es-ES";

        String cacheKey = cleanLang + ":" + cleanText;

        // Trả về từ Cache RAM ngay lập tức nếu đã từng tạo (0ms)
        String cachedAudio = ttsCache.get(cacheKey);
        if (cachedAudio != null) {
            log.info("[TTS CACHE HIT] Trả về audio từ Cache ({}) cho: '{}'", cleanLang, 
                    cleanText.length() > 40 ? cleanText.substring(0, 40) + "..." : cleanText);
            return cachedAudio;
        }

        String url = "http://127.0.0.1:5000/tts";
        
        try {
            log.info("Đang gọi Edge-TTS Python server cho: '{}' ({})", 
                    cleanText.length() > 40 ? cleanText.substring(0, 40) + "..." : cleanText, cleanLang);
            String rawResponse = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", cleanText, "language", cleanLang))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode rootNode = objectMapper.readTree(rawResponse);
            if ("success".equals(rootNode.path("status").asText())) {
                String base64 = rootNode.path("audio_base64").asText();
                if (base64 != null && !base64.isBlank()) {
                    if (ttsCache.size() > 1000) {
                        ttsCache.clear();
                    }
                    ttsCache.put(cacheKey, base64);
                }
                return base64;
            } else {
                throw new Exception("Lỗi từ Python TTS: " + rootNode.path("message").asText());
            }
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Lỗi 500 từ TTS server: {}", e.getResponseBodyAsString());
            throw new Exception("Lỗi từ Python: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Lỗi kết nối tới TTS server: {}", e.getMessage());
            throw new Exception("Lỗi kết nối tới TTS server: " + e.getMessage(), e);
        }
    }
}
