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
import com.example.videocall_marching_language.utils.TextNormalizationUtil;
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

        ScriptDTO dto = ScriptDTO.builder()
                .id(script.getId())
                .title(script.getTitle())
                .language(script.getLanguage())
                .targetDuration(script.getTargetDuration())
                .phoneticContent(phoneticContent)
                .meaningContent(meaningContent)
                .build();
        List<SentenceRoleDTO> roleSentences = SentenceSplitterUtil.parseScriptLines(
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

    public SpeechEvaluationDTO checkSpeech(String originalSentence, String userSentence, int phase) {
        // Chỉ đơn giản là đẩy sang GeminiAIService đã được viết sẵn
        return geminiAIService.evaluateSpeech(originalSentence, userSentence, phase);
    }

    public SpeechEvaluationDTO checkSpeechWithNormalization(
            String originalSentence,
            String userSentence,
            int phase,
            String languageCode) {

        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence(originalSentence, languageCode);
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech(userSentence, languageCode);
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser);

        // MỨC 1: XANH LÁ CÂY (PASS) - Đạt yêu cầu hoàn toàn hoặc chính xác >= 85%
        if (cleanOriginal.equals(cleanUser) || similarity >= 0.85) {
            String sugg = cleanOriginal.equals(cleanUser)
                    ? "Xuất sắc! Bạn đã phát âm chính xác 100%."
                    : "Rất tốt! Bạn đạt độ chính xác " + Math.round(similarity * 100) + "%. Đã đạt yêu cầu câu này!";

            return SpeechEvaluationDTO.builder()
                    .isCorrect(true)
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(originalSentence)
                    .errors(java.util.Collections.emptyList())
                    .suggestion(sugg)
                    .similarity(similarity)
                    .status("PASS")
                    .build();
        }

        // MỨC 2: MÀU VÀNG (NEAR) - Gần đúng (từ 60% đến dưới 85%)
        if (similarity >= 0.60) {
            return SpeechEvaluationDTO.builder()
                    .isCorrect(false)
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(originalSentence)
                    .errors(java.util.Collections.emptyList())
                    .suggestion("Khá tốt! Bạn đạt độ chính xác " + Math.round(similarity * 100) + "%. Bạn có thể nói lại để hoàn thiện hơn hoặc chuyển câu tiếp theo.")
                    .similarity(similarity)
                    .status("NEAR")
                    .build();
        }

        // MỨC 3: MÀU ĐỎ (FAIL) - Sai quá (dưới 60%), yêu cầu nói lại
        SpeechEvaluationDTO aiResult = geminiAIService.evaluateSpeech(originalSentence, userSentence, phase);
        if (aiResult != null && aiResult.isCorrect()) {
            aiResult.setSimilarity(similarity);
            aiResult.setStatus("PASS");
            return aiResult;
        }

        return SpeechEvaluationDTO.builder()
                .isCorrect(false)
                .originalSentence(originalSentence)
                .userSentence(userSentence)
                .correctedSentence((aiResult != null && aiResult.getCorrectedSentence() != null) ? aiResult.getCorrectedSentence() : originalSentence)
                .errors((aiResult != null && aiResult.getErrors() != null) ? aiResult.getErrors() : java.util.List.of("Phát âm chưa đạt yêu cầu"))
                .suggestion("Phát âm chưa chính xác (đạt " + Math.round(similarity * 100) + "%). Bạn hãy nghe lại mẫu và nói lại câu này nhé!")
                .similarity(similarity)
                .status("FAIL")
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