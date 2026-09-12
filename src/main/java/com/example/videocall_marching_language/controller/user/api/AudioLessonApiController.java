package com.example.videocall_marching_language.controller.user.api;

import com.example.videocall_marching_language.dto.audiolesson.*;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.service.audiolesson.AudioLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audio-lessons")
public class AudioLessonApiController {

    private final AudioLessonService audioLessonService;
    private final IUserService userService;

    private Long getCurrentUserId(Authentication authentication) {
        if (authentication != null && authentication.getName() != null) {
            return userService.findByEmail(authentication.getName())
                    .map(User::getId)
                    .orElse(null);
        }
        return null;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAndGenerate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tagId") Long tagId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "language", required = false) String language,
            Authentication authentication) {

        Long userId = getCurrentUserId(authentication);
        log.info("API Upload Audio Lesson: file={}, size={}, tagId={}, title={}, level={}, language={}, userId={}",
                file.getOriginalFilename(), file.getSize(), tagId, title, level, language, userId);

        try {
            AudioLessonDTO created = audioLessonService.createLessonFromAudio(file, tagId, title, userId, level, language);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            log.warn("Dữ liệu đầu vào không hợp lệ: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Lỗi xử lý audio và AI: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Lỗi hệ thống khi xử lý âm thanh"));
        }
    }

    @PostMapping("/evaluate-grammar")
    public ResponseEntity<?> evaluateGrammar(
            @RequestBody GrammarChallengeRequestDTO request,
            Authentication authentication) {

        Long userId = getCurrentUserId(authentication);
        try {
            GrammarChallengeResponseDTO result = audioLessonService.evaluateGrammarChallenge(request, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Lỗi khi chấm điểm ngữ pháp: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Không thể đánh giá câu của bạn lúc này."));
        }
    }

    @PostMapping("/check-exercise")
    public ResponseEntity<?> checkExercise(@RequestBody ExerciseCheckRequestDTO request) {
        try {
            ExerciseCheckResponseDTO result = audioLessonService.checkExercise(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Lỗi khi kiểm tra đáp án: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Lỗi kiểm tra bài tập."));
        }
    }
}
