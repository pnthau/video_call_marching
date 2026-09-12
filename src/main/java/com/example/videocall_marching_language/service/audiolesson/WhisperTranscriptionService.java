package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.WhisperSegmentDTO;
import com.example.videocall_marching_language.entity.UserAiSetting;
import com.example.videocall_marching_language.enums.AIProvider;
import com.example.videocall_marching_language.repository.IUserAiSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhisperTranscriptionService {

    private final RestTemplate restTemplate;
    private final IUserAiSettingRepository userAiSettingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${groq.whisper.url:https://api.groq.com/openai/v1/audio/transcriptions}")
    private String groqWhisperUrl;

    @Value("${groq.whisper.model:whisper-large-v3-turbo}")
    private String defaultWhisperModel;

    @Value("${groq.api.key:}")
    private String globalGroqApiKey;

    public WhisperTranscriptionResult transcribe(MultipartFile file, Long userId) {
        String apiKey = resolveApiKey(userId);
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Chưa cấu hình GROQ API Key. Vui lòng cập nhật API Key trong trang Profile để sử dụng tính năng Whisper.");
        }

        try {
            byte[] fileBytes = file.getBytes();
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.mp3";

            ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return originalFilename;
                }
            };

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(apiKey.trim());

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);
            body.add("model", defaultWhisperModel);
            body.add("response_format", "verbose_json");
            body.add("temperature", "0.0");

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            log.info("===> [WHISPER API REQUEST] Sending audio ({}) to Groq Whisper: model={}", originalFilename, defaultWhisperModel);
            long startTime = System.currentTimeMillis();

            ResponseEntity<String> response = restTemplate.postForEntity(groqWhisperUrl, requestEntity, String.class);
            long duration = System.currentTimeMillis() - startTime;

            log.info("<=== [WHISPER API RESPONSE] Groq Whisper transcribed in {}ms", duration);

            return parseWhisperResponse(response.getBody());

        } catch (IOException e) {
            log.error("Lỗi đọc file audio: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể đọc dữ liệu file audio", e);
        } catch (Exception e) {
            log.error("Lỗi khi gọi Groq Whisper API: {}", e.getMessage(), e);
            throw new RuntimeException("Lỗi bóc tách âm thanh qua Whisper: " + e.getMessage(), e);
        }
    }

    private WhisperTranscriptionResult parseWhisperResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String fullText = root.path("text").asText("");
            String language = root.path("language").asText("ja");
            double duration = root.path("duration").asDouble(0.0);

            List<WhisperSegmentDTO> segments = new ArrayList<>();
            JsonNode segmentsNode = root.path("segments");

            if (segmentsNode.isArray() && !segmentsNode.isEmpty()) {
                int index = 0;
                for (JsonNode seg : segmentsNode) {
                    double start = seg.path("start").asDouble(0.0);
                    double end = seg.path("end").asDouble(0.0);
                    String text = seg.path("text").asText("").trim();

                    if (!text.isEmpty()) {
                        segments.add(WhisperSegmentDTO.builder()
                                .sentenceIndex(index++)
                                .startTime(start)
                                .endTime(end)
                                .text(text)
                                .build());
                    }
                }
            } else if (!fullText.isBlank()) {
                // Fallback nếu không có mảng segments
                segments.add(WhisperSegmentDTO.builder()
                        .sentenceIndex(0)
                        .startTime(0.0)
                        .endTime(duration > 0 ? duration : 5.0)
                        .text(fullText.trim())
                        .build());
            }

            return new WhisperTranscriptionResult(fullText, language, duration, segments);

        } catch (Exception e) {
            log.error("Lỗi parse JSON kết quả Whisper: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể xử lý kết quả trả về từ Whisper: " + e.getMessage(), e);
        }
    }

    private String resolveApiKey(Long userId) {
        if (userId != null) {
            UserAiSetting setting = userAiSettingRepository.findByUserIdAndAiProvider(userId, AIProvider.GROQ).orElse(null);
            if (setting == null) {
                setting = userAiSettingRepository.findByUserIdAndAiProvider(userId, AIProvider.GROG).orElse(null);
            }
            if (setting != null && setting.getAiApiKey() != null && !setting.getAiApiKey().isBlank()) {
                return setting.getAiApiKey();
            }
        }

        if (globalGroqApiKey != null && !globalGroqApiKey.isBlank()) {
            return globalGroqApiKey;
        }

        String envKey = System.getenv("GROQ_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }

        return null;
    }

    public record WhisperTranscriptionResult(String fullText, String language, double duration, List<WhisperSegmentDTO> segments) {}
}
