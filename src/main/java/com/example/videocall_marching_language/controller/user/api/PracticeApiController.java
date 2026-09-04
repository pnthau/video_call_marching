package com.example.videocall_marching_language.controller.user.api;

import com.example.videocall_marching_language.dto.SpeechEvaluationDTO;
import com.example.videocall_marching_language.entity.PracticeHistory;
import com.example.videocall_marching_language.service.PracticeService;
import com.example.videocall_marching_language.service.speech.TtsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/practice")
public class PracticeApiController {

    private final PracticeService practiceService;
    private final TtsService ttsService;

    @PostMapping("/start")
    public ResponseEntity<Long> startPractice(
            @RequestParam Long userId,
            @RequestParam Long scriptId,
            @RequestParam int phase) {

        PracticeHistory history = practiceService.startPractice(userId, scriptId, phase);
        return ResponseEntity.ok(history.getId());
    }

    @PostMapping("/check")
    public ResponseEntity<SpeechEvaluationDTO> checkSpeech(
            @RequestParam String originalSentence,
            @RequestParam String userSentence,
            @RequestParam int phase,
            @RequestParam String languageCode) {

        SpeechEvaluationDTO evaluation = practiceService.checkSpeechWithNormalization(
                originalSentence,
                userSentence,
                phase,
                languageCode
        );
        return ResponseEntity.ok(evaluation);
    }

    @PostMapping("/finish")
    public ResponseEntity<Boolean> finishPractice(
            @RequestParam Long historyId,
            @RequestParam(required = false) String aiFeedback) {

        PracticeHistory history = practiceService.finishPractice(historyId, aiFeedback);
        return ResponseEntity.ok(history.getIsPassed());
    }

    @GetMapping("/tts")
    public ResponseEntity<Map<String, String>> getTtsAudio(
            @RequestParam String text,
            @RequestParam(defaultValue = "ja") String language) {

        String audioBase64 = ttsService.generateAudioBase64(text, language);
        if (audioBase64 != null && !audioBase64.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "audioBase64", audioBase64
            ));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "error", "message", "TTS Server offline"));
    }
}
