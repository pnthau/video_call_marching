package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.WhisperSegmentDTO;
import com.example.videocall_marching_language.dto.audiolesson.WhisperWordDTO;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhisperTranscriptionService {

    private final RestTemplate restTemplate;
    private final IUserAiSettingRepository userAiSettingRepository;
    private final WhisperSanitizerService whisperSanitizerService;
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
            body.add("timestamp_granularities[]", "segment");
            body.add("timestamp_granularities[]", "word");

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

            // Đọc mảng words ở root nếu có
            List<WhisperWordDTO> allWords = new ArrayList<>();
            JsonNode rootWordsNode = root.path("words");
            if (rootWordsNode.isArray()) {
                for (JsonNode wNode : rootWordsNode) {
                    allWords.add(WhisperWordDTO.builder()
                            .word(wNode.path("word").asText(""))
                            .startTime(wNode.path("start").asDouble(0.0))
                            .endTime(wNode.path("end").asDouble(0.0))
                            .build());
                }
            }

            List<WhisperSegmentDTO> segments = new ArrayList<>();
            JsonNode segmentsNode = root.path("segments");

            if (segmentsNode.isArray() && !segmentsNode.isEmpty()) {
                int index = 0;
                for (JsonNode seg : segmentsNode) {
                    double start = seg.path("start").asDouble(0.0);
                    double end = seg.path("end").asDouble(0.0);
                    String text = seg.path("text").asText("").trim();
                    Double noSpeechProb = seg.hasNonNull("no_speech_prob") ? seg.path("no_speech_prob").asDouble() : null;
                    Double avgLogprob = seg.hasNonNull("avg_logprob") ? seg.path("avg_logprob").asDouble() : null;
                    Double compressionRatio = seg.hasNonNull("compression_ratio") ? seg.path("compression_ratio").asDouble() : null;

                    List<WhisperWordDTO> segWords = new ArrayList<>();
                    JsonNode segWordsNode = seg.path("words");
                    if (segWordsNode.isArray() && !segWordsNode.isEmpty()) {
                        for (JsonNode wNode : segWordsNode) {
                            segWords.add(WhisperWordDTO.builder()
                                    .word(wNode.path("word").asText(""))
                                    .startTime(wNode.path("start").asDouble(0.0))
                                    .endTime(wNode.path("end").asDouble(0.0))
                                    .build());
                        }
                    } else if (!allWords.isEmpty()) {
                        for (WhisperWordDTO w : allWords) {
                            if (w.getStartTime() >= start - 0.05 && w.getEndTime() <= end + 0.1) {
                                segWords.add(w);
                            }
                        }
                    }

                    if (!text.isEmpty()) {
                        segments.add(WhisperSegmentDTO.builder()
                                .sentenceIndex(index++)
                                .startTime(start)
                                .endTime(end)
                                .text(text)
                                .noSpeechProb(noSpeechProb)
                                .avgLogprob(avgLogprob)
                                .compressionRatio(compressionRatio)
                                .words(segWords.isEmpty() ? null : segWords)
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

            // Lọc bỏ âm rác, nốt nhạc, thẻ hiệu ứng và ảo giác phụ đề từ Whisper
            List<WhisperSegmentDTO> sanitizedSegments = whisperSanitizerService.sanitizeSegments(segments);

            String cleanFullText = sanitizedSegments.stream()
                    .map(WhisperSegmentDTO::getText)
                    .collect(Collectors.joining(" "));
            if (cleanFullText.isBlank()) {
                cleanFullText = fullText;
            }

            return new WhisperTranscriptionResult(cleanFullText, language, duration, sanitizedSegments);

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
