package com.example.videocall_marching_language.controller.user.api;

import com.example.videocall_marching_language.dto.SpeechEvaluationResponse;
import com.example.videocall_marching_language.dto.tts.TtsAudioRequest;
import com.example.videocall_marching_language.dto.tts.TtsAudioResponse;
import com.example.videocall_marching_language.entity.PracticeHistory;
import com.example.videocall_marching_language.service.script.PracticeService;
import com.example.videocall_marching_language.service.speech.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.client.RestTemplate;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/practice")
public class PracticeApiController {

    private final PracticeService practiceService;
    private final TtsService ttsService;
    private final RestTemplate restTemplate;

    private String defaultGeminiApiKey;

    @PostMapping("/start")
    public ResponseEntity<Long> startPractice(
            @RequestParam Long userId,
            @RequestParam Long scriptId,
            @RequestParam int phase) {

        PracticeHistory history = practiceService.startPractice(userId, scriptId, phase);
        return ResponseEntity.ok(history.getId());
    }

    @PostMapping("/check")
    public ResponseEntity<SpeechEvaluationResponse> checkSpeech(
            @RequestParam String originalSentence,
            @RequestParam String userSentence,
            @RequestParam int phase,
            @RequestParam(required = false, defaultValue = "ja") String languageCode) {

        log.info("Received checkSpeech request: original='{}', user='{}', phase={}, lang='{}'",
                originalSentence, userSentence, phase, languageCode);
        SpeechEvaluationResponse evaluation = practiceService.checkSpeechWithNormalization(
                originalSentence,
                userSentence,
                phase,
                languageCode
        );

        // Giải pháp 1: Backend tạo trước audio Base64 song song cho toàn bộ segments
        // Frontend nhận được kết quả là có sẵn âm thanh bấm phát ngay, loại bỏ Bước 4 từ client
        if (evaluation != null && evaluation.getTtsSegments() != null && !evaluation.getTtsSegments().isEmpty()) {
            evaluation.getTtsSegments().parallelStream().forEach(seg -> {
                if (seg != null && seg.getText() != null && !seg.getText().isBlank()) {
                    try {
                        String b64 = ttsService.generateAudioBase64(seg.getText(), seg.getLang());
                        seg.setAudioBase64(b64);
                    } catch (Exception e) {
                        log.warn("Không thể tạo trước audio TTS cho segment: '{}': {}", seg.getText(), e.getMessage());
                    }
                }
            });
        }

        log.info("checkSpeech result: isCorrect={}, errors={}", evaluation != null && evaluation.isCorrect(), evaluation != null ? evaluation.getErrors() : null);

        return ResponseEntity.ok(evaluation);
    }

    @PostMapping("/finish")
    public ResponseEntity<Boolean> finishPractice(
            @RequestParam Long historyId,
            @RequestParam(required = false) String aiFeedback) {

        PracticeHistory history = practiceService.finishPractice(historyId, aiFeedback);
        return ResponseEntity.ok(history.getIsPassed());
    }

    @PostMapping("/tts")
    public ResponseEntity<TtsAudioResponse> getTtsAudio(
            @RequestBody TtsAudioRequest request) {

        try {
            String text = request != null ? request.getText() : null;
            String language = (request != null && request.getLanguage() != null && !request.getLanguage().isBlank())
                    ? request.getLanguage()
                    : "ja";

            String audioBase64 = ttsService.generateAudioBase64(text, language);

            if (audioBase64 != null && !audioBase64.isEmpty()) {
                return ResponseEntity.ok(TtsAudioResponse.builder()
                        .status("success")
                        .audioBase64(audioBase64)
                        .build());
            }
            return ResponseEntity.ok(TtsAudioResponse.builder()
                    .status("fallback")
                    .message("Edge-TTS không tạo được âm thanh, chuyển sang fallback")
                    .build());
        } catch (Exception e) {
            log.warn("Edge-TTS server không khả dụng, chuyển sang chế độ fallback: {}", e.getMessage());
            return ResponseEntity.ok(TtsAudioResponse.builder()
                    .status("fallback")
                    .message("Edge-TTS offline: " + e.getMessage())
                    .build());
        }
    }


}
