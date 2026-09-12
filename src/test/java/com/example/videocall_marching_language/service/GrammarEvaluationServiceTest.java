package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.audiolesson.GrammarChallengeRequestDTO;
import com.example.videocall_marching_language.dto.audiolesson.GrammarChallengeResponseDTO;
import com.example.videocall_marching_language.service.ai.AiProxyService;
import com.example.videocall_marching_language.service.audiolesson.GrammarEvaluationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrammarEvaluationServiceTest {

    @Test
    void feedbackIsTrimmedToAtMost30Words() {
        AiProxyService aiProxyService = mock(AiProxyService.class);
        GrammarEvaluationService service = new GrammarEvaluationService(aiProxyService);

        // Giả lập AI trả lời một đoạn văn dài hơn 40 từ
        String longFeedback = "Câu của bạn rất tốt tuy nhiên bạn cần chú ý hơn về trợ từ vì trong tiếng Nhật trợ từ đóng vai trò rất quan trọng để xác định chủ ngữ và vị ngữ trong câu giao tiếp hàng ngày giúp cho người nghe hiểu rõ ý định của bạn hơn rất nhiều khi đàm thoại thực tế.";
        String mockJsonResponse = String.format("""
            {
              "isCorrect": true,
              "score": 90,
              "feedback": "%s",
              "correctedSentence": "Correct",
              "suggestedSentence": "Suggested"
            }
        """, longFeedback);

        when(aiProxyService.chat(anyLong(), any())).thenReturn(mockJsonResponse);

        GrammarChallengeRequestDTO request = GrammarChallengeRequestDTO.builder()
                .grammarPoint("〜です")
                .grammarFormula("N + です")
                .challengePrompt("Thử thách: Hãy tự giới thiệu nghề nghiệp của mình")
                .language("ja")
                .userSentence("私は学生です。")
                .build();

        GrammarChallengeResponseDTO response = service.evaluateSentence(1L, request);

        assertNotNull(response);
        assertNotNull(response.getFeedback());
        String[] words = response.getFeedback().trim().split("\\s+");
        assertTrue(words.length <= 31, "Feedback không được vượt quá 30 từ (tính cả ellipsis): " + words.length);
    }
}
