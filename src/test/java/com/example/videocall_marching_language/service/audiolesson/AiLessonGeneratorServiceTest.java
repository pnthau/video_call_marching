package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.GeneratedLessonDTO;
import com.example.videocall_marching_language.dto.audiolesson.GeneratedSentenceDTO;
import com.example.videocall_marching_language.dto.audiolesson.WhisperSegmentDTO;
import com.example.videocall_marching_language.service.ai.AiProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AiLessonGeneratorServiceTest {

    private AiLessonGeneratorService aiLessonGeneratorService;
    private AiProxyService mockAiProxyService;
    private WhisperSanitizerService whisperSanitizerService;

    @BeforeEach
    public void setUp() {
        mockAiProxyService = Mockito.mock(AiProxyService.class);
        whisperSanitizerService = Mockito.mock(WhisperSanitizerService.class);
        aiLessonGeneratorService = new AiLessonGeneratorService(mockAiProxyService, whisperSanitizerService);
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi không có segment giọng nói hợp lệ nào sau khi sanitize")
    public void testGenerateLessonContent_ThrowsException_WhenNoCleanSegments() {
        List<WhisperSegmentDTO> rawSegments = List.of(
                WhisperSegmentDTO.builder().sentenceIndex(0).startTime(0.0).endTime(1.0).text("[Music]").build()
        );

        when(whisperSanitizerService.sanitizeSegments(rawSegments)).thenReturn(Collections.emptyList());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                aiLessonGeneratorService.generateLessonContent(1L, rawSegments, "en", "Hobbies")
        );

        assertTrue(ex.getMessage().contains("Không phát hiện nội dung giọng nói hợp lệ"));
    }

    @Test
    @DisplayName("Sinh bài học Tiếng Anh thành công, gửi prompt IPA và bảo toàn timestamps từ Whisper")
    public void testGenerateLessonContent_EnglishSuccess_AndPreservesTimestamps() {
        WhisperSegmentDTO origSeg = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(1.2)
                .endTime(3.8)
                .text("I love learning English.")
                .build();
        List<WhisperSegmentDTO> cleanSegments = List.of(origSeg);

        when(whisperSanitizerService.sanitizeSegments(any())).thenReturn(cleanSegments);

        String aiJsonResponse = """
        {
          "lessonTitle": "Sở thích học ngoại ngữ",
          "sentences": [
            {
              "sentenceIndex": 0,
              "startTime": 0.0,
              "endTime": 2.0,
              "originalText": "I love learning English.",
              "phonetic": "/aɪ lʌv ˈlɜːnɪŋ ˈɪŋɡlɪʃ/",
              "vietnameseMeaning": "Tôi thích học tiếng Anh.",
              "grammarPoint": "Gerund as Object",
              "explanation": "Động từ love đi kèm V-ing để chỉ sở thích.",
              "formula": "S + love + V-ing",
              "challengeTargetSentence": "She is interested in learning foreign languages.",
              "sentenceChallengePrompt": "Thử thách: Đặt câu nói cô ấy rất quan tâm đến việc học ngoại ngữ.",
              "keyWordsJson": "[{\\"word\\":\\"interested\\",\\"ipa\\":\\"/ˈɪn.trɪs.tɪd/\\",\\"meaning\\":\\"quan tâm, thích thú\\"}]",
              "exerciseType": "LISTENING_FILL_BLANK",
              "question": "She is interested ___ learning foreign languages.",
              "options": ["in", "on", "at", "for"],
              "correctAnswer": "in",
              "exerciseExplanation": "Cấu trúc be interested in"
            }
          ]
        }
        """;

        when(mockAiProxyService.chat(eq(1L), any())).thenReturn(aiJsonResponse);

        GeneratedLessonDTO result = aiLessonGeneratorService.generateLessonContent(1L, cleanSegments, "en", "Sở thích");

        assertNotNull(result);
        assertEquals("Sở thích học ngoại ngữ", result.getLessonTitle());
        assertEquals(1, result.getSentences().size());

        GeneratedSentenceDTO sentence = result.getSentences().get(0);
        // Timestamps từ LLM (0.0 và 2.0) phải được ghi đè bằng timestamps thực tế từ Whisper (1.2 và 3.8)
        assertEquals(1.2, sentence.getStartTime());
        assertEquals(3.8, sentence.getEndTime());
        assertEquals("I love learning English.", sentence.getOriginalText());
        assertEquals("/aɪ lʌv ˈlɜːnɪŋ ˈɪŋɡlɪʃ/", sentence.getPhonetic());

        // Kiểm tra Prompt gửi cho LLM là chuẩn tiếng Anh
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockAiProxyService).chat(eq(1L), promptCaptor.capture());
        String sentPrompt = promptCaptor.getValue();

        assertTrue(sentPrompt.contains("Tiếng Anh (English / en)"));
        assertTrue(sentPrompt.contains("International Phonetic Alphabet"));
        assertTrue(sentPrompt.contains("\"word\""));
        assertTrue(sentPrompt.contains("\"ipa\""));
        assertTrue(sentPrompt.contains("\"meaning\""));
        assertFalse(sentPrompt.contains("juushichi"));
    }

    @Test
    @DisplayName("Sinh bài học Tiếng Nhật thành công, tự động bóc tách Markdown ```json và giữ cấu trúc Romaji/Hiragana")
    public void testGenerateLessonContent_JapaneseSuccess_WithMarkdownCodeBlock() {
        WhisperSegmentDTO origSeg = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(0.5)
                .endTime(2.5)
                .text("こんにちは、田中です。")
                .build();
        List<WhisperSegmentDTO> cleanSegments = List.of(origSeg);

        when(whisperSanitizerService.sanitizeSegments(any())).thenReturn(cleanSegments);

        String aiResponseWithMarkdown = """
        ```json
        {
          "lessonTitle": "Chào hỏi tiếng Nhật",
          "sentences": [
            {
              "sentenceIndex": 0,
              "startTime": 0.5,
              "endTime": 2.5,
              "originalText": "こんにちは、田中です。",
              "phonetic": "Konnichiwa, Tanaka desu (こんにちは、たなかです)",
              "vietnameseMeaning": "Xin chào, tôi là Tanaka.",
              "grammarPoint": "Danh từ + です",
              "explanation": "Dùng để khẳng định danh từ lịch sự.",
              "formula": "N + です",
              "challengeTargetSentence": "日曜日にコンサートがあります。",
              "sentenceChallengePrompt": "Thử thách: Đặt câu thông báo có buổi hòa nhạc.",
              "keyWordsJson": "[{\\"kanji\\":\\"日曜日\\",\\"hiragana\\":\\"にちようび\\",\\"romaji\\":\\"nichiyoubi\\",\\"meaning\\":\\"Chủ Nhật\\"}]",
              "exerciseType": "LISTENING_FILL_BLANK",
              "question": "こんにちは、田中___。",
              "options": ["です", "ます", "でした", "ません"],
              "correctAnswer": "です",
              "exerciseExplanation": "Chọn です"
            }
          ]
        }
        ```
        """;

        when(mockAiProxyService.chat(eq(2L), any())).thenReturn(aiResponseWithMarkdown);

        GeneratedLessonDTO result = aiLessonGeneratorService.generateLessonContent(2L, cleanSegments, "ja", "Chào hỏi");

        assertNotNull(result);
        assertEquals("Chào hỏi tiếng Nhật", result.getLessonTitle());
        assertEquals(1, result.getSentences().size());

        GeneratedSentenceDTO sentence = result.getSentences().get(0);
        assertEquals("Konnichiwa, Tanaka desu (こんにちは、たなかです)", sentence.getPhonetic());
        assertTrue(sentence.getKeyWordsJson().contains("日曜日"));

        // Kiểm tra Prompt gửi cho LLM là chuẩn tiếng Nhật
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockAiProxyService).chat(eq(2L), promptCaptor.capture());
        String sentPrompt = promptCaptor.getValue();

        assertTrue(sentPrompt.contains("Tiếng Nhật (ja)"));
        assertTrue(sentPrompt.contains("Romaji (Hiragana)"));
        assertTrue(sentPrompt.contains("juushichi"));
        assertTrue(sentPrompt.contains("\"kanji\""));
        assertTrue(sentPrompt.contains("\"hiragana\""));
    }

    @Test
    @DisplayName("Tự động kích hoạt buildFallbackLesson khi AI trả về dữ liệu không hợp lệ hoặc lỗi JSON")
    public void testGenerateLessonContent_FallbackWhenAiResponseIsInvalidJson() {
        WhisperSegmentDTO origSeg1 = WhisperSegmentDTO.builder().sentenceIndex(0).startTime(1.0).endTime(2.5).text("Hello").build();
        WhisperSegmentDTO origSeg2 = WhisperSegmentDTO.builder().sentenceIndex(1).startTime(3.0).endTime(4.5).text("How are you?").build();
        List<WhisperSegmentDTO> cleanSegments = List.of(origSeg1, origSeg2);

        when(whisperSanitizerService.sanitizeSegments(any())).thenReturn(cleanSegments);
        when(mockAiProxyService.chat(any(), any())).thenReturn("Xin lỗi, tôi không thể xử lý yêu cầu lúc này. Rate limit exceeded.");

        GeneratedLessonDTO fallbackResult = aiLessonGeneratorService.generateLessonContent(1L, cleanSegments, "en", "General");

        assertNotNull(fallbackResult);
        assertEquals("Bài học Audio Tương tác", fallbackResult.getLessonTitle());
        assertEquals(2, fallbackResult.getSentences().size());

        GeneratedSentenceDTO s1 = fallbackResult.getSentences().get(0);
        assertEquals(0, s1.getSentenceIndex());
        assertEquals(1.0, s1.getStartTime());
        assertEquals(2.5, s1.getEndTime());
        assertEquals("Hello", s1.getOriginalText());
        assertEquals("Hello", s1.getCorrectAnswer());
        assertEquals("LISTENING_FILL_BLANK", s1.getExerciseType());

        GeneratedSentenceDTO s2 = fallbackResult.getSentences().get(1);
        assertEquals(1, s2.getSentenceIndex());
        assertEquals(3.0, s2.getStartTime());
        assertEquals(4.5, s2.getEndTime());
        assertEquals("How are you?", s2.getOriginalText());
    }

    @Test
    @DisplayName("Tự động bù đắp originalText nếu JSON phản hồi từ AI bị thiếu hoặc để rỗng")
    public void testGenerateLessonContent_PopulateOriginalText_WhenAiOmitsIt() {
        WhisperSegmentDTO origSeg = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(1.0)
                .endTime(3.0)
                .text("Good morning everyone.")
                .build();
        List<WhisperSegmentDTO> cleanSegments = List.of(origSeg);

        when(whisperSanitizerService.sanitizeSegments(any())).thenReturn(cleanSegments);

        String jsonWithoutOriginalText = """
        {
          "lessonTitle": "Chào buổi sáng",
          "sentences": [
            {
              "sentenceIndex": 0,
              "originalText": "",
              "phonetic": "/ɡʊd ˈmɔːnɪŋ ˈevriwʌn/",
              "vietnameseMeaning": "Chào buổi sáng mọi người."
            }
          ]
        }
        """;

        when(mockAiProxyService.chat(any(), any())).thenReturn(jsonWithoutOriginalText);

        GeneratedLessonDTO result = aiLessonGeneratorService.generateLessonContent(1L, cleanSegments, "en", "Greeting");

        assertNotNull(result);
        assertEquals("Good morning everyone.", result.getSentences().get(0).getOriginalText());
    }

    @Test
    @DisplayName("buildPrompt phân nhánh chính xác cho các biến thể của ngôn ngữ tiếng Anh và tiếng Nhật")
    public void testBuildPrompt_LanguageRouting() {
        String segmentsData = "[0] (start: 0.00s, end: 2.00s): Test\\n";

        // Tiếng Anh (en, EN, english, English, en-US)
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "en", "Test").contains("Tiếng Anh (English / en)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "EN", "Test").contains("Tiếng Anh (English / en)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "english", "Test").contains("Tiếng Anh (English / en)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "English", "Test").contains("Tiếng Anh (English / en)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "en-US", "Test").contains("Tiếng Anh (English / en)"));

        // Tiếng Nhật (ja, JA, japanese, null, rỗng)
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "ja", "Test").contains("Tiếng Nhật (ja)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "JA", "Test").contains("Tiếng Nhật (ja)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "japanese", "Test").contains("Tiếng Nhật (ja)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, null, "Test").contains("Tiếng Nhật (ja)"));
        assertTrue(aiLessonGeneratorService.buildPrompt(segmentsData, "", "Test").contains("Tiếng Nhật (ja)"));
    }

    @Test
    @DisplayName("buildPrompt sử dụng chủ đề mặc định phù hợp khi topicName là null")
    public void testBuildPrompt_DefaultTopicName() {
        String segmentsData = "[0] (start: 0.00s, end: 2.00s): Test\\n";

        String promptEn = aiLessonGeneratorService.buildPrompt(segmentsData, "en", null);
        assertTrue(promptEn.contains("Giao tiếp tiếng Anh"));

        String promptJa = aiLessonGeneratorService.buildPrompt(segmentsData, "ja", null);
        assertTrue(promptJa.contains("Giao tiếp tiếng Nhật"));
    }
}
