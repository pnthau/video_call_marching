package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.*;
import com.example.videocall_marching_language.entity.PracticeHistory;
import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.repository.IPracticeHistoryRepository;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.utils.SentenceSplitterUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PracticeService {
    private final IScriptRepository scriptRepository;
    private final IUserRepository   userRepository;
    private  final IPracticeHistoryRepository practiceHistoryRepository;
    private final GeminiAIService geminiAIService;
    public List<ScriptDTO> findScriptsByTagAndPhase(ScriptRequestDTO request) {
        if (request == null || request.getTags() == null || request.getTags().isEmpty()) {
            return scriptRepository.findAll().stream().map(s -> ScriptDTO.builder()
                    .id(s.getId())
                    .title(s.getTitle())
                    .language(s.getLanguage())
                    .targetDuration(s.getTargetDuration())
                    .build()
            ).toList();
        }
        List<IScriptSummaryView> summaries = scriptRepository.findByTagNameIn(request.getTags());
        return summaries.stream().map(s -> ScriptDTO.builder()
                .id(s.getId())
                .title(s.getTitle())
                .language(s.getLanguage())
                .targetDuration(s.getTargetDuration())
                .build()
        ).toList();
    }

    public ScriptDTO findScriptById(long id) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(
                        () -> new RuntimeException("Not found script by id : " + id)
                );
        ScriptDTO dto = ScriptDTO.builder()
                .id(script.getId())
                .title(script.getTitle())
                .language(script.getLanguage())
                .targetDuration(script.getTargetDuration())
                .build();
        List<String> rawSentences = SentenceSplitterUtil.splitIntoSentences(
                script.getContent(),
                script.getLanguage() != null ? script.getLanguage() : "ja"
        );
        List<SentenceRoleDTO> roleSentences = SentenceSplitterUtil.assignRoles(rawSentences);
        dto.setSentences(roleSentences);

        return dto;
    }

    public PracticeHistory startPractice(Long userId, Long scriptId, int phase) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy User"));

        Script script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Script"));

        // Tạo mới lịch sử luyện tập
        PracticeHistory history = PracticeHistory.builder()
                .user(user)
                .script(script)
                .phase(phase)
                .startTime(java.time.LocalDateTime.now()) // Bắt đầu bấm giờ
                .isPassed(false)
                .build();

        return practiceHistoryRepository.save(history);
    }

    public SpeechEvaluationDTO checkSpeech(String originalSentence, String userSentence, int phase) {
        // Chỉ đơn giản là đẩy sang GeminiAIService đã được viết sẵn
        return geminiAIService.evaluateSpeech(originalSentence, userSentence, phase);
    }

    public PracticeHistory finishPractice(Long historyId, String aiFeedback) {
        PracticeHistory history = practiceHistoryRepository.findById(historyId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Lịch sử luyện tập"));

        // 1. Chốt giờ kết thúc
        history.setEndTime(LocalDateTime.now());

        // 2. Tính số giây đã trôi qua
        long duration = Duration.between(history.getStartTime(), history.getEndTime()).getSeconds();
        history.setDurationInSeconds((int) duration);

        // 3. Đánh giá Đỗ/Trượt dựa trên targetDuration
        boolean isPassed = false;
        if (history.getScript().getTargetDuration() != null) {
            isPassed = (duration <= history.getScript().getTargetDuration());
        }
        history.setIsPassed(isPassed);
        history.setAiFeedback(aiFeedback);

        return practiceHistoryRepository.save(history);
    }


}