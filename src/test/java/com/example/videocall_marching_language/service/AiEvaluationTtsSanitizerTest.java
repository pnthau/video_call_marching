package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.SpeechEvaluationResponse;
import com.example.videocall_marching_language.dto.TtsSegmentResponse;
import com.example.videocall_marching_language.service.ai.AiEvaluationService;
import com.example.videocall_marching_language.service.ai.AiProxyService;
import com.example.videocall_marching_language.repository.IUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class AiEvaluationTtsSanitizerTest {

    @Test
    public void testTtsSegmentsSanitization_SplitsJapaneseFromVietnamese() {
        AiProxyService mockAiProxy = Mockito.mock(AiProxyService.class);
        IUserRepository mockUserRepo = Mockito.mock(IUserRepository.class);

        String mockGeminiResponse = "{\n" +
                "  \"isCorrect\": false,\n" +
                "  \"status\": \"NEAR\",\n" +
                "  \"correctedSentence\": \"らーめんひとつとぎょーざをおねがいします。\",\n" +
                "  \"errors\": [\"Phát âm nhầm &quot;ギョーザ&quot; thành &quot;ぎょうや&quot;\"],\n" +
                "  \"suggestion\": \"Gần đúng! Bạn đã phát âm &quot;ギョーザ&quot; thành &quot;ぎょうや&quot;. Hãy chú ý luyện tập lại từ này nhé.\",\n" +
                "  \"ttsSegments\": [\n" +
                "    { \"text\": \"Gần đúng! Bạn đã phát âm &quot;ギョーザ&quot; thành &quot;ぎょうや&quot;. Hãy chú ý luyện tập lại từ này nhé.\", \"lang\": \"vi-VN\" },\n" +
                "    { \"text\": \"らーめんひとつとぎょーざをおねがいします。\", \"lang\": \"ja-JP\" }\n" +
                "  ]\n" +
                "}";

        when(mockAiProxy.chat(any(), any())).thenReturn(mockGeminiResponse);

        AiEvaluationService evaluationService = new AiEvaluationService(mockAiProxy, mockUserRepo);

        SpeechEvaluationResponse response = evaluationService.evaluateSpeech(
                "らーめんひとつとぎょーざをおねがいします。",
                "らーめんひとつとぎょうやをおねがいします。",
                1,
                "ja"
        );

        assertNotNull(response);
        assertFalse(response.isCorrect());
        assertEquals("NEAR", response.getStatus());

        // Kiểm tra HTML entity &quot; đã được unescape thành "
        assertEquals("Gần đúng! Bạn đã phát âm \"ギョーザ\" thành \"ぎょうや\". Hãy chú ý luyện tập lại từ này nhé.", response.getSuggestion());
        assertEquals(1, response.getErrors().size());
        assertEquals("Phát âm nhầm \"ギョーザ\" thành \"ぎょうや\"", response.getErrors().get(0));

        // Kiểm tra rawAiResponse và debugDetails đã được đính kèm
        assertNotNull(response.getRawAiResponse());
        assertTrue(response.getRawAiResponse().contains("ギョーザ"));
        assertNotNull(response.getAiPrompt());
        assertNotNull(response.getDebugDetails());

        // Kiểm tra mảng TTS segments: Đoạn tiếng Nhật phải được tách sang ja-JP
        List<TtsSegmentResponse> segments = response.getTtsSegments();
        assertNotNull(segments);
        assertTrue(segments.size() >= 3, "TTS segments phải được tách thành nhiều đoạn tương ứng với từng ngôn ngữ");

        System.out.println("=== KẾT QUẢ PHÂN TÁCH TTS SEGMENTS ===");
        for (int i = 0; i < segments.size(); i++) {
            TtsSegmentResponse seg = segments.get(i);
            System.out.println("Segment #" + (i + 1) + " [" + seg.getLang() + "] : " + seg.getText());
        }

        // Segment ギョーザ phải có lang là ja-JP
        boolean hasJaGyoza = segments.stream().anyMatch(s -> s.getText().contains("ギョーザ") && "ja-JP".equalsIgnoreCase(s.getLang()));
        assertTrue(hasJaGyoza, "Từ tiếng Nhật 'ギョーザ' phải được gán ngôn ngữ ja-JP");

        // Segment ぎょうや phải có lang là ja-JP
        boolean hasJaGyouya = segments.stream().anyMatch(s -> s.getText().contains("ぎょうや") && "ja-JP".equalsIgnoreCase(s.getLang()));
        assertTrue(hasJaGyouya, "Từ tiếng Nhật 'ぎょうや' phải được gán ngôn ngữ ja-JP");
    }

    @Test
    public void testTtsSegmentsAlternatingBilingualFormat_PassesThroughAndNormalizesLang() {
        AiProxyService mockAiProxy = Mockito.mock(AiProxyService.class);
        IUserRepository mockUserRepo = Mockito.mock(IUserRepository.class);

        String mockResponse = "{\n" +
                "  \"isCorrect\": false,\n" +
                "  \"status\": \"NEAR\",\n" +
                "  \"correctedSentence\": \"らーめんひとつとぎょーざをおねがいします。\",\n" +
                "  \"errors\": [\"Phát âm nhầm 'ギョーザ' thành 'ぎょうや'\"],\n" +
                "  \"suggestion\": \"Bạn đã phát âm nhầm 'ギョーザ' thành 'ぎょうや'. Hãy chú ý nghe câu mẫu nhé.\",\n" +
                "  \"ttsSegments\": [\n" +
                "    { \"lang\": \"vi\", \"text\": \"Bạn đã phát âm nhầm \" },\n" +
                "    { \"lang\": \"ja\", \"text\": \"ギョーザ\" },\n" +
                "    { \"lang\": \"vi\", \"text\": \" thành \" },\n" +
                "    { \"lang\": \"ja\", \"text\": \"ぎょうや\" },\n" +
                "    { \"lang\": \"vi\", \"text\": \". Hãy chú ý nhé. Hãy nghe lại câu mẫu:\" },\n" +
                "    { \"lang\": \"ja\", \"text\": \"らーめんひとつとぎょーざをおねがいします。\" }\n" +
                "  ]\n" +
                "}";

        when(mockAiProxy.chat(any(), any())).thenReturn(mockResponse);

        AiEvaluationService evaluationService = new AiEvaluationService(mockAiProxy, mockUserRepo);

        SpeechEvaluationResponse response = evaluationService.evaluateSpeech(
                "らーめんひとつとぎょーざをおねがいします。",
                "らーめんひとつとぎょうやをおねがいします。",
                1,
                "ja"
        );

        assertNotNull(response);
        assertEquals(6, response.getTtsSegments().size());

        List<TtsSegmentResponse> segs = response.getTtsSegments();
        assertEquals("vi-VN", segs.get(0).getLang());
        assertEquals("Bạn đã phát âm nhầm", segs.get(0).getText());

        assertEquals("ja-JP", segs.get(1).getLang());
        assertEquals("ギョーザ", segs.get(1).getText());

        assertEquals("vi-VN", segs.get(2).getLang());
        assertEquals("thành", segs.get(2).getText());

        assertEquals("ja-JP", segs.get(3).getLang());
        assertEquals("ぎょうや", segs.get(3).getText());

        assertEquals("vi-VN", segs.get(4).getLang());
        assertEquals(". Hãy chú ý nhé. Hãy nghe lại câu mẫu:", segs.get(4).getText());

        assertEquals("ja-JP", segs.get(5).getLang());
        assertEquals("らーめんひとつとぎょーざをおねがいします。", segs.get(5).getText());
    }
}
