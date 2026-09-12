package com.example.videocall_marching_language.service.script;

import com.example.videocall_marching_language.dto.*;
import com.example.videocall_marching_language.dto.script.IScriptSummaryView;
import com.example.videocall_marching_language.dto.script.ScriptRequest;
import com.example.videocall_marching_language.dto.script.ScriptResponse;
import com.example.videocall_marching_language.dto.script.SentenceRoleResponse;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.PracticeHistory;
import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IPracticeHistoryRepository;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.utils.SentenceSplitterUtil;
import com.example.videocall_marching_language.utils.TextNormalizationUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeService {
    private final IScriptRepository scriptRepository;
    private final IUserRepository userRepository;
    private final IPracticeHistoryRepository practiceHistoryRepository;
    private final ITagRepository tagRepository;
    private final ObjectMapper objectMapper;
    private final com.example.videocall_marching_language.service.ai.AiEvaluationService aiEvaluationService;

    public List<ScriptResponse> findScriptsByCriteria(ScriptRequest request) {
        if (request == null) {
            request = new ScriptRequest();
        }

        Long tagId = request.getTagId();
        String tagName = (request.getTag() != null && !request.getTag().isBlank()) ? request.getTag().trim() : null;
        List<String> tagNames = request.getTags();
        boolean hasTagNames = (tagNames != null && !tagNames.isEmpty());

        String language = (request.getLanguage() != null && !request.getLanguage().isBlank() && !"all".equalsIgnoreCase(request.getLanguage()))
                ? request.getLanguage().trim().toLowerCase()
                : null;

        String level = (request.getLevel() != null && !request.getLevel().isBlank() && !"all".equalsIgnoreCase(request.getLevel()))
                ? request.getLevel().trim().toLowerCase()
                : null;

        List<Script> scripts = scriptRepository.findByCriteria(
                tagId,
                tagName,
                hasTagNames,
                hasTagNames ? tagNames : List.of(""),
                language,
                level,
                request.getMinDuration(),
                request.getMaxDuration()
        );

        return scripts.stream().map(s -> ScriptResponse.builder()
                .id(s.getId())
                .title(s.getTitle())
                .language(s.getLanguage())
                .level(s.getLevel())
                .targetDuration(s.getTargetDuration())
                .phoneticContent(s.getPhoneticContent())
                .meaningContent(s.getMeaningContent())
                .tagId(s.getTag() != null ? s.getTag().getId() : null)
                .tagName(s.getTag() != null ? s.getTag().getName() : null)
                .build()
        ).toList();
    }

    public List<ScriptResponse> findScriptsByTagAndPhase(ScriptRequest request) {
        return findScriptsByCriteria(request);
    }

    public List<Tag> getAvailableTopics() {
        return tagRepository.findByTagCategoryType(TagCategoryType.TOPIC);
    }

    public List<TopicWithCountDTO> getTopicsWithScriptCount() {
        return scriptRepository.findTopicsWithScriptCount();
    }

    public String getTopicsWithCountAsJson() {
        try {
            List<TopicWithCountDTO> list = getTopicsWithScriptCount();
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("Lỗi serialize danh sách topics sang JSON: {}", e.getMessage());
            return "[]";
        }
    }

    public List<String> getAvailableLanguages() {
        List<String> languages = scriptRepository.findDistinctLanguages();
        if (languages == null || languages.isEmpty()) {
            return List.of("ja", "en");
        }
        return languages;
    }

    public ScriptResponse findScriptById(long id) {
        Script script = scriptRepository.findById(id)
                .orElseThrow(
                        () -> new RuntimeException("Not found script by id : " + id)
                );

        // Đảm bảo luôn có phoneticContent (nếu DB cũ đang null hoặc rỗng thì tự động bổ sung và lưu lại)
        String phoneticContent = script.getPhoneticContent();
        if (phoneticContent == null || phoneticContent.trim().isEmpty()) {
            String title = script.getTitle() != null ? script.getTitle().toLowerCase() : "";
            String content = script.getContent() != null ? script.getContent() : "";

            if (title.contains("giới thiệu") || content.contains("初めまして") || content.contains("田中")) {
                phoneticContent = "A: はじめまして、わたしはたなかです。べとなむからきました。\nB: はじめまして、たなかさん。どうぞよろしくおねがいします。\nA: こちらこそ、よろしくおねがいいたします。";
            } else if (title.contains("mua sắm") || title.contains("combini") || content.contains("お弁当") || content.contains("レジ袋")) {
                phoneticContent = "A: いらっしゃいませ！おべんとうをあたためますか？\nB: はい、おねがいします。\nA: レジぶくろはごりようになりますか？\nB: いいえ、だいじょうぶです。\nA: おかいけいはごひゃくえんになります。\nB: ペイペイではらいます。";
            } else if (title.contains("gọi món") || title.contains("quán ăn") || title.contains("roleplay") || title.contains("đóng vai") || title.contains("restaurant") || content.contains("注文") || content.contains("ラーメン")) {
                phoneticContent = "A: すみません、ちゅうもんをおねがいします。\nB: はい、なににいたしましょうか？\nA: らーめんひとつとぎょーざをおねがいします。\nB: かしこまりました。おのみものはいかがですか？\nA: おみずをいっぱいください。";
            } else if (title.contains("hỏi đường") || title.contains("ga tàu") || content.contains("東京駅") || content.contains("信号")) {
                phoneticContent = "A: すみません、とうきょうえきはどこですか？\nB: このみちをまっすぐいって、しんごうをみぎにまがってください。\nA: あるいてどれくらいかかりますか？\nB: だいたいごふんくらいですよ。\nA: ありがとうございます。たすかりました。";
            } else if (title.contains("english") || title.contains("coffee") || "en".equalsIgnoreCase(script.getLanguage()) || content.contains("How are you") || content.contains("coffee")) {
                phoneticContent = "A: /həˈloʊ! haʊ ɑːr juː ˈduːɪŋ təˈdeɪ?/\nB: /haɪ! aɪ æm ˈduːɪŋ wɛl, θæŋk juː. haʊ əˈbaʊt juː?/\nA: /aɪ æm ˈprɪti ɡʊd. ɑːr juː friː ðɪs ˌæftərˈnuːn?/\nB: /jɛs, aɪ æm friː. lɛts ɡræb ə ˈkɔːfi təˈɡɛðər!/\nA: /ðæt saʊndz ˈwʌndərfəl!/";
            } else {
                phoneticContent = script.getContent();
            }
            script.setPhoneticContent(phoneticContent);
            scriptRepository.save(script);
        }

        // Đảm bảo luôn có meaningContent (nghĩa tiếng Việt của kịch bản)
        String meaningContent = script.getMeaningContent();
        if (meaningContent == null || meaningContent.trim().isEmpty()) {
            String title = script.getTitle() != null ? script.getTitle().toLowerCase() : "";
            String content = script.getContent() != null ? script.getContent() : "";

            if (title.contains("giới thiệu") || content.contains("初めまして") || content.contains("田中")) {
                meaningContent = "A: Rất vui được gặp bạn, tôi là Tanaka. Tôi đến từ Việt Nam.\nB: Rất vui được gặp bạn, anh Tanaka. Rất mong được giúp đỡ.\nA: Chính tôi mới là người mong được giúp đỡ.";
            } else if (title.contains("mua sắm") || title.contains("combini") || content.contains("お弁当") || content.contains("レジ袋")) {
                meaningContent = "A: Xin chào quý khách! Quý khách có muốn hâm nóng hộp cơm bento không?\nB: Vâng, xin vui lòng hâm giúp tôi.\nA: Quý khách có dùng túi nilon không ạ?\nB: Không, tôi không cần túi đâu.\nA: Tổng tiền thanh toán là 500 yên.\nB: Tôi sẽ thanh toán bằng PayPay.";
            } else if (title.contains("gọi món") || title.contains("quán ăn") || title.contains("roleplay") || title.contains("đóng vai") || title.contains("restaurant") || content.contains("注文") || content.contains("ラーメン")) {
                meaningContent = "A: Xin lỗi, cho tôi gọi món với.\nB: Vâng, quý khách muốn dùng món gì ạ?\nA: Cho tôi một bát ramen và một đĩa gyoza.\nB: Tôi đã hiểu. Quý khách có muốn gọi đồ uống gì không?\nA: Cho tôi một ly nước lọc.";
            } else if (title.contains("hỏi đường") || title.contains("ga tàu") || content.contains("東京駅") || content.contains("信号")) {
                meaningContent = "A: Xin lỗi, ga Tokyo ở đâu vậy ạ?\nB: Bạn đi thẳng con đường này, rồi rẽ phải ở cột đèn giao thông nhé.\nA: Đi bộ mất khoảng bao lâu vậy ạ?\nB: Tầm khoảng 5 phút thôi bạn.\nA: Xin cảm ơn bạn rất nhiều. May quá.";
            } else if (title.contains("english") || title.contains("coffee") || "en".equalsIgnoreCase(script.getLanguage()) || content.contains("How are you") || content.contains("coffee")) {
                meaningContent = "A: Xin chào! Hôm nay bạn thế nào?\nB: Chào bạn! Tôi khỏe, cảm ơn bạn. Còn bạn thì sao?\nA: Tôi cũng rất ổn. Chiều nay bạn có rảnh không?\nB: Có, tôi rảnh. Cùng đi uống cà phê nhé!\nA: Nghe tuyệt vời đấy!";
            } else {
                meaningContent = "";
            }
            script.setMeaningContent(meaningContent);
            scriptRepository.save(script);
        }

        ScriptResponse dto = ScriptResponse.builder()
                .id(script.getId())
                .title(script.getTitle())
                .language(script.getLanguage())
                .level(script.getLevel())
                .targetDuration(script.getTargetDuration())
                .phoneticContent(phoneticContent)
                .meaningContent(meaningContent)
                .tagId(script.getTag() != null ? script.getTag().getId() : null)
                .tagName(script.getTag() != null ? script.getTag().getName() : null)
                .build();
        List<SentenceRoleResponse> roleSentences = SentenceSplitterUtil.parseScriptLines(
                script.getContent(),
                phoneticContent,
                meaningContent
        );
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

    public SpeechEvaluationResponse checkSpeech(String originalSentence, String userSentence, int phase) {
        return aiEvaluationService.evaluateSpeech(originalSentence, userSentence, phase);
    }

    public SpeechEvaluationResponse checkSpeechWithNormalization(
            String originalSentence,
            String userSentence,
            int phase,
            String languageCode) {

        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence(originalSentence, languageCode);
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech(userSentence, languageCode);
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser, languageCode);

        // Lớp 1: Khớp chuẩn xác 100% sau khi chuẩn hóa chuỗi (Fast-Path cho tất cả ngôn ngữ - 0ms, tiết kiệm chi phí)
        if (cleanOriginal.equals(cleanUser)) {
            String sugg = "Xuất sắc! Bạn đã phát âm chính xác 100%.";
            log.info("[CHECK SPEECH] Fast-path exact match 100% for original: '{}'", originalSentence);

            return SpeechEvaluationResponse.builder()
                    .isCorrect(true)
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(originalSentence)
                    .errors(java.util.Collections.emptyList())
                    .suggestion(sugg)
                    .similarity(1.0)
                    .status("PASS")
                    .rawAiResponse("FAST_PATH_EXACT_MATCH (Khớp 100%, không cần gọi Gemini AI)")
                    .aiPrompt("N/A - Chuỗi chuẩn hóa khớp 100%")
                    .debugDetails("Fast-path match: cleanOriginal='" + cleanOriginal + "', cleanUser='" + cleanUser + "'")
                    .ttsSegments(java.util.List.of(
                            TtsSegmentResponse.builder().text(sugg).lang("vi-VN").build()
                    ))
                    .build();
        }

        // Lớp 2: Không khớp 100% -> Chuyển hoàn toàn việc phân tích và đánh giá cho Gemini AI
        SpeechEvaluationResponse aiResult = aiEvaluationService.evaluateSpeech(originalSentence, userSentence, phase, languageCode);

        boolean isPassStatus = (aiResult != null && (aiResult.isCorrect() || "PASS".equalsIgnoreCase(aiResult.getStatus())));
        String finalStatus;
        if (isPassStatus) {
            finalStatus = "PASS";
        } else if (aiResult != null && "NEAR".equalsIgnoreCase(aiResult.getStatus())) {
            // "Gần đúng" (Màu vàng) đòi hỏi nói được phần lớn câu (similarity >= 0.55)
            // Nếu bỏ sót hơn nửa câu (similarity < 0.55), đây là "sai nhiều" (Màu đỏ - FAIL)
            if (similarity < 0.55) {
                finalStatus = "FAIL";
            } else {
                finalStatus = "NEAR";
            }
        } else if (aiResult != null && "FAIL".equalsIgnoreCase(aiResult.getStatus())) {
            // Nếu AI đánh giá FAIL nhưng người dùng nói giống >= 75% -> Gần đúng (NEAR)
            if (similarity >= 0.75) {
                finalStatus = "NEAR";
            } else {
                finalStatus = "FAIL";
            }
        } else {
            // Fallback nếu AI không trả status rõ ràng
            if (similarity >= 0.60) {
                finalStatus = "NEAR";
            } else {
                finalStatus = "FAIL";
            }
        }

        String fallbackSuggestion = switch (finalStatus) {
            case "PASS" -> "Bạn đã phát âm chính xác.";
            case "NEAR" -> "Bạn phát âm gần đúng rồi, chỉ sai sót một chút thôi!";
            default -> "Phát âm sai nhiều. Bạn hãy nghe lại mẫu và nói lại câu này nhé!";
        };

        log.info("[CHECK SPEECH] Evaluated: similarity={}, rawAiStatus={}, finalStatus={}", 
                similarity, (aiResult != null ? aiResult.getStatus() : "null"), finalStatus);

        return SpeechEvaluationResponse.builder()
                .isCorrect(finalStatus.equals("PASS"))
                .originalSentence(originalSentence)
                .userSentence(userSentence)
                .correctedSentence((aiResult != null && aiResult.getCorrectedSentence() != null) ? aiResult.getCorrectedSentence() : originalSentence)
                .errors((aiResult != null && aiResult.getErrors() != null) ? aiResult.getErrors() : java.util.List.of("Phát âm chưa đạt yêu cầu"))
                .suggestion((aiResult != null && aiResult.getSuggestion() != null && !aiResult.getSuggestion().isEmpty()) 
                        ? aiResult.getSuggestion() 
                        : fallbackSuggestion)
                .similarity(similarity)
                .status(finalStatus)
                .ttsSegments(aiResult != null ? aiResult.getTtsSegments() : null)
                .rawAiResponse(aiResult != null ? aiResult.getRawAiResponse() : null)
                .aiPrompt(aiResult != null ? aiResult.getAiPrompt() : null)
                .debugDetails("Evaluated with similarity=" + similarity + ", aiStatus=" + (aiResult != null ? aiResult.getStatus() : "null") + ", finalStatus=" + finalStatus + (aiResult != null && aiResult.getDebugDetails() != null ? " | " + aiResult.getDebugDetails() : ""))
                .build();
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