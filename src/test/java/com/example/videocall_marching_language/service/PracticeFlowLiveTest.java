package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.SpeechEvaluationResponse;
import com.example.videocall_marching_language.dto.tts.TtsSegmentResponse;
import com.example.videocall_marching_language.entity.PracticeHistory;
import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.repository.IPracticeHistoryRepository;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.script.PracticeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("live")
public class PracticeFlowLiveTest {

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private IUserRepository userRepository;

    @Autowired
    private IScriptRepository scriptRepository;

    @Autowired
    private IPracticeHistoryRepository practiceHistoryRepository;

    @Test
    @Transactional
    public void testFullPracticeFlow() {
        System.out.println("========== BẮT ĐẦU TEST LUỒNG PRACTICE ==========");

        // 1. Lấy User và Script từ DB
        List<User> users = userRepository.findAll();
        List<Script> scripts = scriptRepository.findAll();

        if (users.isEmpty() || scripts.isEmpty()) {
            System.out.println("Không đủ dữ liệu User hoặc Script trong DB để test!");
            return;
        }

        User user = users.get(0);
        Script script = scripts.get(0);
        System.out.println("Sử dụng User ID: " + user.getId() + " - Email: " + user.getEmail());
        System.out.println("Sử dụng Script ID: " + script.getId() + " - Tiêu đề: " + script.getTitle());

        // Mock Authentication cho AI Proxy
        org.springframework.security.core.userdetails.User principal = new org.springframework.security.core.userdetails.User(
                user.getEmail(), "", java.util.Collections.emptyList());
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Bước 1: Start Practice
        System.out.println("--> Bước 1: Start Practice");
        PracticeHistory history = practiceService.startPractice(user.getId(), script.getId(), 1);
        assertNotNull(history);
        assertNotNull(history.getId());
        System.out.println("  Tạo PracticeHistory thành công, ID = " + history.getId());

        // Bước 2: Đánh giá giọng nói (Check Speech) - Trường hợp Hoàn hảo
        System.out.println("--> Bước 2: Check Speech (Đúng 100%)");
        String original = "Hello, how are you?";
        String userSpeechPerfect = "Hello, how are you?";
        SpeechEvaluationResponse eval1 = practiceService.checkSpeechWithNormalization(original, userSpeechPerfect, 1, "en");
        assertTrue(eval1.isCorrect());
        assertEquals("PASS", eval1.getStatus());
        System.out.println("  Trạng thái: " + eval1.getStatus() + " - Suggestion: " + eval1.getSuggestion());

        // Bước 3: Đánh giá giọng nói (Check Speech) - Trường hợp Đọc thiếu nhiều (Rớt) tiếng Anh
        System.out.println("--> Bước 3: Check Speech (Sai, đọc rớt từ tiếng Anh)");
        String userSpeechBad = "Hello";
        SpeechEvaluationResponse eval2 = practiceService.checkSpeechWithNormalization(original, userSpeechBad, 1, "en");
        assertFalse(eval2.isCorrect());
        assertEquals("FAIL", eval2.getStatus());
        System.out.println("  Trạng thái: " + eval2.getStatus() + " - Errors: " + eval2.getErrors());
        if (eval2.getTtsSegments() != null) {
            System.out.println("  ==> Các đoạn TTS cho trình đọc <==");
            for (TtsSegmentResponse seg : eval2.getTtsSegments()) {
                System.out.println("    [" + seg.getLang() + "] : " + seg.getText());
            }
        }

        // Bước 3.5: Đánh giá tiếng Nhật - Đọc 1 phần câu (Lỗi mà user đã báo -> FAIL)
        System.out.println("--> Bước 3.5: Check Speech Tiếng Nhật (Chỉ đọc 1 chữ trong 1 câu)");
        String jaOriginal = "いらっしゃいませ！お弁当を温めますか？"; // 15 chars
        String jaUserPartial = "いらっしゃいませ"; // 8 chars
        SpeechEvaluationResponse evalJa = practiceService.checkSpeechWithNormalization(jaOriginal, jaUserPartial, 1, "ja");
        
        System.out.println("  Trạng thái Nhật: " + evalJa.getStatus() + " - isCorrect: " + evalJa.isCorrect());
        assertFalse(evalJa.isCorrect(), "Test Tiếng Nhật PHẢI RỚT khi đọc 1 nửa câu");
        assertEquals("FAIL", evalJa.getStatus(), "Test Tiếng Nhật PHẢI FAIL khi đọc 1 nửa câu");

        // Bước 3.6: Đánh giá trường hợp GẦN ĐÚNG (NEAR - Vàng)
        System.out.println("--> Bước 3.6: Check Speech (Gần đúng - NEAR)");
        String jaUserNear = "いらっしゃいませ！お弁当温めますか？"; // Thiếu mỗi trợ từ "を" (similarity > 0.85)
        SpeechEvaluationResponse evalNear = practiceService.checkSpeechWithNormalization(jaOriginal, jaUserNear, 1, "ja");
        System.out.println("  Trạng thái Gần đúng: " + evalNear.getStatus() + " - Similarity: " + evalNear.getSimilarity());
        assertTrue("NEAR".equals(evalNear.getStatus()) || "PASS".equals(evalNear.getStatus()), "Chỉ thiếu trợ từ nhỏ phải đạt NEAR hoặc PASS");

        // Bước 4: Finish Practice
        System.out.println("--> Bước 4: Finish Practice");
        try {
            Thread.sleep(2000); // Đợi 2s để có duration
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        String aiFeedback = "Người dùng phát âm khá tốt phần chào hỏi, nhưng cần luyện tập thêm câu dài.";
        PracticeHistory finishedHistory = practiceService.finishPractice(history.getId(), aiFeedback);
        
        assertNotNull(finishedHistory.getEndTime());
        assertTrue(finishedHistory.getDurationInSeconds() >= 2);
        assertEquals(aiFeedback, finishedHistory.getAiFeedback());
        System.out.println("  Kết thúc Practice thành công. Thời lượng: " + finishedHistory.getDurationInSeconds() + " giây.");
        System.out.println("  Kết quả Đỗ/Trượt (dựa vào Target Duration): " + finishedHistory.getIsPassed());

        System.out.println("========== TEST LUỒNG PRACTICE THÀNH CÔNG TỐT ĐẸP ==========");
    }
}
