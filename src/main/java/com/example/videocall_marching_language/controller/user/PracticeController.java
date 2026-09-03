package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.ScriptDTO;
import com.example.videocall_marching_language.dto.ScriptRequestDTO;
import com.example.videocall_marching_language.dto.SpeechEvaluationDTO;
import com.example.videocall_marching_language.entity.PracticeHistory;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.service.PracticeService;
import com.example.videocall_marching_language.service.speech.TtsService;
import com.example.videocall_marching_language.utils.TextNormalizationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/practice")
public class PracticeController {
    private final PracticeService  practiceService;
    private final TtsService ttsService;
    @GetMapping("/scripts")
    public String getScriptsByTags(
            ScriptRequestDTO request,
            Model model )
    {
        List<ScriptDTO> scriptList  = practiceService.findScriptsByTagAndPhase(request);
        model.addAttribute("scripts",scriptList);
        model.addAttribute("phase", request.getPhase());
        return "users/scripts/list";
    }

    @GetMapping("/scripts/{id}")
    public String getScriptById(
            @PathVariable int id,
            Model model)
    {
        ScriptDTO scriptDTO = practiceService.findScriptById(id);
        model.addAttribute("script", scriptDTO);
        return "users/scripts/learn";
    }

    @PostMapping("/start")
    @ResponseBody
    public Long startPractice(@RequestParam Long userId,
                              @RequestParam Long scriptId,
                              @RequestParam int phase) {
        // Gọi Service và trả về cái ID của lịch sử luyện tập vừa tạo
        PracticeHistory history = practiceService.startPractice(userId, scriptId, phase);
        return history.getId();
    }

    @PostMapping("/check")
    @ResponseBody
    public SpeechEvaluationDTO checkSpeech(
            @RequestParam String originalSentence,
            @RequestParam String userSentence,
            @RequestParam int phase,
            @RequestParam String languageCode) {

        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence(originalSentence, languageCode);
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech(userSentence, languageCode);
        if (cleanOriginal.equals(cleanUser)) {
            return SpeechEvaluationDTO.builder()
                    .isCorrect(true)
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(originalSentence)
                    .errors(Collections.emptyList())
                    .suggestion("Xuất sắc! Bạn đã phát âm chính xác 100%.")
                    .build();
        }
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser);

        if (similarity >= 0.90) {
            return SpeechEvaluationDTO.builder()
                    .isCorrect(true)
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(originalSentence)
                    .errors(Collections.emptyList())
                    .suggestion("Rất tốt! Câu của bạn đạt độ chính xác " + Math.round(similarity * 100) + "%.")
                    .build();
        }
        return practiceService.checkSpeech(originalSentence, userSentence, phase);
    }

    @PostMapping("/finish")
    @ResponseBody
    public Boolean finishPractice(
            @RequestParam Long historyId,
            @RequestParam(required = false) String aiFeedback) {

        PracticeHistory history = practiceService.finishPractice(historyId, aiFeedback);
        return history.getIsPassed();
    }

    @GetMapping("/tts")
    @ResponseBody
    public ResponseEntity<Map<String,String>> getTtsAudio(
            @RequestParam String text,
            @RequestParam(defaultValue = "ja") String language
    ){
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
