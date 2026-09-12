package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.SpeechEvaluationResponse;
import com.example.videocall_marching_language.entity.UserAiSetting;
import com.example.videocall_marching_language.enums.AIProvider;
import com.example.videocall_marching_language.repository.IUserAiSettingRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.ai.AiEvaluationService;
import com.example.videocall_marching_language.service.ai.GroqAiServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

@SpringBootTest
public class GroqLiveTest {

    @Autowired
    private GroqAiServiceImpl groqAiService;

    @Autowired
    private AiEvaluationService aiEvaluationService;

    @Autowired
    private IUserAiSettingRepository userAiSettingRepository;

    @Autowired
    private IUserRepository userRepository;

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void testGroqCall() {
        System.out.println("==================================================");
        System.out.println("  KIỂM TRA GROQ API SERVICE (MODEL QWEN)");
        System.out.println("==================================================");
        List<UserAiSetting> allSettings = userAiSettingRepository.findAll();
        System.out.println("Tổng số cấu hình AI tìm thấy trong DB: " + allSettings.size());

        UserAiSetting groqSetting = null;
        for (UserAiSetting setting : allSettings) {
            System.out.println("-> User ID: " + setting.getUser().getId() +
                    " | Provider: " + setting.getAiProvider() +
                    " | Key: " + (setting.getAiApiKey() != null && setting.getAiApiKey().length() > 8
                    ? setting.getAiApiKey().substring(0, 8) + "..."
                    : "EMPTY"));
            if (setting.getAiProvider() == AIProvider.GROQ || setting.getAiProvider() == AIProvider.GROG) {
                groqSetting = setting;
            }
        }

        if (groqSetting == null || groqSetting.getAiApiKey() == null || groqSetting.getAiApiKey().isBlank()) {
            System.out.println("⚠️ CHƯA TÌM THẤY API KEY CHO PROVIDER GROG TRONG DATABASE!");
            return;
        }

        String groqKey = groqSetting.getAiApiKey().trim();

        // 1. TEST GỌI TRỰC TIẾP QUA GroqAiServiceImpl
        System.out.println("\n--- [BƯỚC 1] Gửi test request trực tiếp tới Groq API (Qwen model) ---");
        long start1 = System.currentTimeMillis();
        try {
            String prompt = "Bạn là một AI trợ giảng. Hãy trả về duy nhất một chuỗi JSON thuần túy có dạng: {\"status\": \"OK\", \"message\": \"Xin chào từ Qwen trên Groq!\", \"model\": \"qwen-2.5-32b\"}";
            String response = groqAiService.generateResponse(prompt, groqKey);
            long duration1 = System.currentTimeMillis() - start1;
            System.out.println("✅ GROQ PHẢN HỒI THÀNH CÔNG TRONG " + duration1 + "ms!");
            System.out.println("Dữ liệu nhận về:\n" + response);
        } catch (Exception e) {
            long duration1 = System.currentTimeMillis() - start1;
            System.out.println("❌ GROQ GỌI TRỰC TIẾP THẤT BẠI (" + duration1 + "ms): " + e.getMessage());
        }

        // 2. TEST GỌI QUA AiEvaluationService ĐẦY ĐỦ LUỒNG HỆ THỐNG
        System.out.println("\n--- [BƯỚC 2] Test qua toàn bộ luồng AiEvaluationService với User ---");
        com.example.videocall_marching_language.entity.User user = groqSetting.getUser();
        if (user != null) {
            user.setActiveAiProvider(AIProvider.GROQ);
            userRepository.save(user);
            String email = user.getEmail();
            org.springframework.security.core.userdetails.User principal = new org.springframework.security.core.userdetails.User(
                    email, "", java.util.Collections.emptyList());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null);
            SecurityContextHolder.getContext().setAuthentication(auth);

            long start2 = System.currentTimeMillis();
            try {
                SpeechEvaluationResponse eval = aiEvaluationService.evaluateSpeech("お水を一杯ください", "おみずをください", 1, "ja");
                long duration2 = System.currentTimeMillis() - start2;
                System.out.println("✅ ĐÁNH GIÁ PHÁT ÂM QUA GROQ THÀNH CÔNG (" + duration2 + "ms)!");
                System.out.println("Is Correct: " + eval.isCorrect());
                System.out.println("Status: " + eval.getStatus());
                System.out.println("Suggestion: " + eval.getSuggestion());
                System.out.println("TTS Segments count: " + (eval.getTtsSegments() != null ? eval.getTtsSegments().size() : 0));
                System.out.println("Raw AI Response: " + eval.getRawAiResponse());
            } catch (Exception e) {
                long duration2 = System.currentTimeMillis() - start2;
                System.out.println("❌ ĐÁNH GIÁ PHÁT ÂM THẤT BẠI (" + duration2 + "ms): " + e.getMessage());
            }
        }
        System.out.println("==================================================");
    }
}
