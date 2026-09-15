package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.SpeechEvaluationResponse;
import com.example.videocall_marching_language.entity.UserAiSetting;
import com.example.videocall_marching_language.repository.IUserAiSettingRepository;
import com.example.videocall_marching_language.service.ai.AiEvaluationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

@SpringBootTest
@Tag("live")
public class AiLiveTest {

    @Autowired
    private AiEvaluationService aiEvaluationService;

    @Autowired
    private IUserAiSettingRepository userAiSettingRepository;

    @Autowired
    private com.example.videocall_marching_language.repository.IUserRepository userRepository;

    @Test
    @org.springframework.transaction.annotation.Transactional
    public void testAiEvaluationReturnsJson() {
        // 1. Tìm một User đã cấu hình API Key trong DB
        List<UserAiSetting> settings = userAiSettingRepository.findAll();
        System.out.println("========== BẮT ĐẦU TEST AI ==========");
        if (settings.isEmpty()) {
            System.out.println("KHÔNG TÌM THẤY USER NÀO CÓ API KEY TRONG DB!");
        } else {
            UserAiSetting setting = settings.get(0);
            System.out.println("Đã tìm thấy User ID: " + setting.getUser().getId() + " sử dụng Provider: " + setting.getAiProvider());
            
            // Lấy email của User
            com.example.videocall_marching_language.entity.User dbUser = setting.getUser();
            String email = dbUser != null ? dbUser.getEmail() : "test@test.com";
            System.out.println("Mocking auth cho email: " + email);
            
            // Giả lập User đăng nhập để AiEvaluationService có thể lấy được ID
            org.springframework.security.core.userdetails.User principal = new org.springframework.security.core.userdetails.User(
                    email, "", java.util.Collections.emptyList());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        // 2. Test hàm evaluateSpeech (yêu cầu trả về JSON)
        String original = "おはようございます";
        String userSpeech = "お";
        
        System.out.println("Câu gốc: " + original);
        System.out.println("Người dùng nói: " + userSpeech);
        System.out.println("Gửi request sang AI...");
        
        long startTime = System.currentTimeMillis();
        SpeechEvaluationResponse result = aiEvaluationService.evaluateSpeech(original, userSpeech, 1);
        long endTime = System.currentTimeMillis();
        
        System.out.println("========== KẾT QUẢ TỪ AI (" + (endTime - startTime) + "ms) ==========");
        System.out.println("Is Correct: " + result.isCorrect());
        System.out.println("Errors: " + result.getErrors());
        System.out.println("Suggestion: " + result.getSuggestion());
        System.out.println("Corrected: " + result.getCorrectedSentence());
        System.out.println("=========================================");
    }
}
