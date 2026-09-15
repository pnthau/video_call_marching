package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.GeneratedLessonDTO;
import com.example.videocall_marching_language.dto.audiolesson.WhisperSegmentDTO;
import com.example.videocall_marching_language.service.ai.AiProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WhisperSanitizerServiceTest {

    private WhisperSanitizerService sanitizerService;

    @BeforeEach
    public void setUp() {
        sanitizerService = new WhisperSanitizerService();
    }

    @Test
    @DisplayName("Chặn các thẻ âm thanh không lời trong ngoặc như [Music], (applause), （拍手）")
    public void testFiltersOutEnclosedSoundTags() {
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("[Music]")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("[music]")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("(applause)")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("（拍手）")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("【音楽】")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("*laughter*")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("[cough]")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("(silence)")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("（笑い声）")));
    }

    @Test
    @DisplayName("Làm sạch nốt nhạc và thẻ inline nhưng vẫn giữ lại lời thoại hợp lệ")
    public void testCleansInlineSoundTagsAndMusicNotesFromValidSpeech() {
        WhisperSegmentDTO seg1 = buildSegment("♪ Good morning everyone! [music]");
        assertFalse(sanitizerService.isNoiseSegment(seg1));
        assertEquals("Good morning everyone!", sanitizerService.cleanText(seg1.getText()));

        WhisperSegmentDTO seg2 = buildSegment("（拍手） こんにちは、みなさん ♪");
        assertFalse(sanitizerService.isNoiseSegment(seg2));
        assertEquals("こんにちは、みなさん", sanitizerService.cleanText(seg2.getText()));
    }

    @Test
    @DisplayName("Chặn các ảo giác phụ đề / YouTube quen thuộc của Whisper (EN, JA, VI)")
    public void testFiltersOutCannedHallucinations() {
        // Tiếng Anh
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Thank you for watching.")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Thanks for watching!")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Please like and subscribe to my channel.")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Subtitles by Amara.org")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Transcribed by the community")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("See you next time!")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Bye bye.")));

        // Tiếng Nhật
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("ご視聴ありがとうございました。")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("ご視聴いただきありがとうございました")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("チャンネル登録よろしくお願いします！")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("高評価とチャンネル登録お願いします")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("ご覧いただきありがとうございます")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("字幕: 佐藤")));

        // Tiếng Việt
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Cảm ơn các bạn đã theo dõi video.")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Hãy nhớ đăng ký kênh nhé.")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("Phụ đề bởi dịch giả.")));
    }

    @Test
    @DisplayName("Chặn vòng lặp lặp từ (Whisper Degeneration Stuttering)")
    public void testFiltersOutRepetitionLoops() {
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("you you you you you you")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("the the the the")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("yeah yeah yeah yeah")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("ああああああああ")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("wwwww")));
    }

    @Test
    @DisplayName("Chặn dấu câu đơn thuần và khoảng trắng")
    public void testFiltersOutPunctuationOnlyAndWhitespace() {
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("...")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("--")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("~")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("   ")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("、、、")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("。。。")));
        assertTrue(sanitizerService.isNoiseSegment(buildSegment("♪ ♪ ♫")));
    }

    @Test
    @DisplayName("Chặn dựa vào metadata chất lượng của Whisper (noSpeechProb, compressionRatio)")
    public void testFiltersOutByWhisperMetadata() {
        WhisperSegmentDTO highNoSpeech = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(1.0)
                .endTime(2.5)
                .text("Ah")
                .noSpeechProb(0.85)
                .build();
        assertTrue(sanitizerService.isNoiseSegment(highNoSpeech));

        WhisperSegmentDTO highCompression = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(1.0)
                .endTime(2.5)
                .text("Loop loop loop")
                .compressionRatio(2.9)
                .build();
        assertTrue(sanitizerService.isNoiseSegment(highCompression));

        WhisperSegmentDTO lowConfidenceNoSpeech = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(1.0)
                .endTime(2.5)
                .text("mumbling")
                .avgLogprob(-1.6)
                .noSpeechProb(0.45)
                .build();
        assertTrue(sanitizerService.isNoiseSegment(lowConfidenceNoSpeech));
    }

    @Test
    @DisplayName("Chuỗi xử lý sanitizeSegments lọc bỏ âm rác và đánh lại index tuần tự")
    public void testSanitizeSegments_FullFlow() {
        List<WhisperSegmentDTO> raw = new ArrayList<>();
        raw.add(WhisperSegmentDTO.builder().sentenceIndex(0).startTime(0.0).endTime(1.5).text("[Music]").build());
        raw.add(WhisperSegmentDTO.builder().sentenceIndex(1).startTime(1.5).endTime(3.2).text("♪ Hello, welcome to today's lesson! [applause]").build());
        raw.add(WhisperSegmentDTO.builder().sentenceIndex(2).startTime(3.2).endTime(4.0).text("you you you you you").build());
        raw.add(WhisperSegmentDTO.builder().sentenceIndex(3).startTime(4.0).endTime(6.5).text("今日はいい天気ですね。").build());
        raw.add(WhisperSegmentDTO.builder().sentenceIndex(4).startTime(6.5).endTime(7.8).text("ご視聴ありがとうございました。").build());
        raw.add(WhisperSegmentDTO.builder().sentenceIndex(5).startTime(7.8).endTime(8.2).text("...").build());

        List<WhisperSegmentDTO> cleaned = sanitizerService.sanitizeSegments(raw);

        assertEquals(2, cleaned.size(), "Chỉ 2 câu hợp lệ được giữ lại");

        WhisperSegmentDTO first = cleaned.get(0);
        assertEquals(0, first.getSentenceIndex(), "Đánh lại index = 0");
        assertEquals("Hello, welcome to today's lesson!", first.getText());
        assertEquals(1.5, first.getStartTime());
        assertEquals(3.2, first.getEndTime());

        WhisperSegmentDTO second = cleaned.get(1);
        assertEquals(1, second.getSentenceIndex(), "Đánh lại index = 1");
        assertEquals("今日はいい天気ですね。", second.getText());
        assertEquals(4.0, second.getStartTime());
        assertEquals(6.5, second.getEndTime());
    }

    @Test
    @DisplayName("AiLessonGeneratorService tích hợp WhisperSanitizerService ngăn chặn âm rác truyền sang LLM")
    public void testAiLessonGeneratorServiceWithSanitizer() {
        AiProxyService mockAiProxy = Mockito.mock(AiProxyService.class);
        AiLessonGeneratorService generatorService = new AiLessonGeneratorService(mockAiProxy, sanitizerService);

        List<WhisperSegmentDTO> mixedSegments = new ArrayList<>();
        mixedSegments.add(WhisperSegmentDTO.builder().sentenceIndex(0).startTime(0.0).endTime(1.0).text("[Music]").build());
        mixedSegments.add(WhisperSegmentDTO.builder().sentenceIndex(1).startTime(1.0).endTime(3.0).text("こんにちは、田中です。").build());
        mixedSegments.add(WhisperSegmentDTO.builder().sentenceIndex(2).startTime(3.0).endTime(4.0).text("ご視聴ありがとうございました").build());

        String fakeAiJson = """
        {
          "lessonTitle": "Giới thiệu bản thân",
          "sentences": [
            {
              "sentenceIndex": 0,
              "startTime": 1.0,
              "endTime": 3.0,
              "originalText": "こんにちは、田中です。",
              "phonetic": "Konnichiwa, Tanaka desu.",
              "vietnameseMeaning": "Xin chào, tôi là Tanaka.",
              "grammarPoint": "Cấu trúc Danh từ + です",
              "explanation": "Dùng để khẳng định danh từ.",
              "formula": "N + です",
              "challengeTargetSentence": "こんにちは、山田です。",
              "sentenceChallengePrompt": "Thử thách: Giới thiệu mình là Yamada.",
              "keyWordsJson": "[]",
              "exerciseType": "LISTENING_FILL_BLANK",
              "question": "こんにちは、田中___。",
              "options": ["です", "ます", "でした", "ません"],
              "correctAnswer": "です",
              "exerciseExplanation": "Chọn です"
            }
          ]
        }
        """;

        when(mockAiProxy.chat(eq(1L), any())).thenReturn(fakeAiJson);

        GeneratedLessonDTO result = generatorService.generateLessonContent(1L, mixedSegments, "ja", "Chào hỏi");

        assertNotNull(result);
        assertEquals("Giới thiệu bản thân", result.getLessonTitle());
        assertEquals(1, result.getSentences().size());

        // Kiểm tra Prompt gửi cho LLM chỉ chứa câu hợp lệ, KHÔNG chứa [Music] hoặc [ご視聴ありがとうございました]
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockAiProxy).chat(eq(1L), promptCaptor.capture());
        String sentPrompt = promptCaptor.getValue();

        assertTrue(sentPrompt.contains("こんにちは、田中です。"));
        assertFalse(sentPrompt.contains("[Music]"));
        assertFalse(sentPrompt.contains("ご視聴ありがとうございました"));
    }

    @Test
    @DisplayName("Xóa tiền tố số track 5.28 hoặc 1. ở đầu câu và dịch chuyển startTime chuẩn xác")
    public void testStripIntroPrefix_NumericTrack528AndAdjustTime() {
        WhisperSegmentDTO seg1 = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(0.0)
                .endTime(4.0)
                .text("5.28 こんにちは、田中です。")
                .build();

        List<WhisperSegmentDTO> cleaned = sanitizerService.sanitizeSegments(List.of(seg1));
        assertEquals(1, cleaned.size());
        WhisperSegmentDTO res1 = cleaned.get(0);
        assertEquals("こんにちは、田中です。", res1.getText());
        assertTrue(res1.getStartTime() > 0.0, "startTime phải được dịch chuyển qua khỏi '5.28'");
        assertEquals(4.0, res1.getEndTime());

        WhisperSegmentDTO seg2 = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(10.0)
                .endTime(14.0)
                .text("1. What is your name?")
                .build();

        List<WhisperSegmentDTO> cleaned2 = sanitizerService.sanitizeSegments(List.of(seg2));
        assertEquals(1, cleaned2.size());
        WhisperSegmentDTO res2 = cleaned2.get(0);
        assertEquals("What is your name?", res2.getText());
        assertTrue(res2.getStartTime() > 10.0, "startTime phải được dịch chuyển qua khỏi '1. '");
        assertEquals(14.0, res2.getEndTime());
    }

    @Test
    @DisplayName("Điều chỉnh startTime và endTime chuẩn xác đến từng mili-giây khi có word timestamps")
    public void testStripIntroPrefix_WordLevelTimestamps() {
        List<com.example.videocall_marching_language.dto.audiolesson.WhisperWordDTO> words = List.of(
                com.example.videocall_marching_language.dto.audiolesson.WhisperWordDTO.builder()
                        .word("5.28").startTime(0.0).endTime(0.85).build(),
                com.example.videocall_marching_language.dto.audiolesson.WhisperWordDTO.builder()
                        .word("こんにちは、").startTime(1.25).endTime(2.40).build(),
                com.example.videocall_marching_language.dto.audiolesson.WhisperWordDTO.builder()
                        .word("田中です。").startTime(2.50).endTime(3.90).build()
        );

        WhisperSegmentDTO seg = WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(0.0)
                .endTime(4.2)
                .text("5.28 こんにちは、田中です。")
                .words(words)
                .build();

        List<WhisperSegmentDTO> cleaned = sanitizerService.sanitizeSegments(List.of(seg));
        assertEquals(1, cleaned.size());

        WhisperSegmentDTO res = cleaned.get(0);
        assertEquals("こんにちは、田中です。", res.getText());
        assertEquals(1.25, res.getStartTime(), 0.001, "startTime phải nhảy chính xác đến từ 'こんにちは' (1.25s)");
        assertEquals(3.90, res.getEndTime(), 0.001, "endTime phải lấy chính xác từ cuối '田中です。' (3.90s)");
    }

    @Test
    @DisplayName("Xóa các nhãn giới thiệu bài học / câu hỏi trong tiếng Nhật và tiếng Anh")
    public void testStripIntroPrefix_LanguagePatterns() {
        assertEquals("田中さんはどこですか。", sanitizerService.sanitizeSegments(List.of(buildSegment("1番、田中さんはどこですか。"))).get(0).getText());
        assertEquals("これは本です。", sanitizerService.sanitizeSegments(List.of(buildSegment("第1課。これは本です。"))).get(0).getText());
        assertEquals("Nice to meet you.", sanitizerService.sanitizeSegments(List.of(buildSegment("Track 5.28: Nice to meet you."))).get(0).getText());
        assertEquals("Where do you live?", sanitizerService.sanitizeSegments(List.of(buildSegment("Question 1. Where do you live?"))).get(0).getText());
        assertEquals("Xin chào các bạn.", sanitizerService.sanitizeSegments(List.of(buildSegment("Bài 1: Xin chào các bạn."))).get(0).getText());
        assertEquals("お元気ですか。", sanitizerService.sanitizeSegments(List.of(buildSegment("① お元気ですか。"))).get(0).getText());
    }

    @Test
    @DisplayName("Loại bỏ hoàn toàn các segment chỉ chứa số thứ tự hoặc nhãn giới thiệu đơn thuần")
    public void testDropSegmentWhenOnlyIntro() {
        assertTrue(sanitizerService.sanitizeSegments(List.of(buildSegment("5.28"))).isEmpty());
        assertTrue(sanitizerService.sanitizeSegments(List.of(buildSegment("1."))).isEmpty());
        assertTrue(sanitizerService.sanitizeSegments(List.of(buildSegment("Track 5.28"))).isEmpty());
        assertTrue(sanitizerService.sanitizeSegments(List.of(buildSegment("第1課"))).isEmpty());
        assertTrue(sanitizerService.sanitizeSegments(List.of(buildSegment("Lesson 1."))).isEmpty());
        assertTrue(sanitizerService.sanitizeSegments(List.of(buildSegment("問題1"))).isEmpty());
    }

    @Test
    @DisplayName("Không xóa nhầm các số hợp lệ là thành phần câu nói bình thường")
    public void testPreserveLegitimateNumbersInSentence() {
        WhisperSegmentDTO seg1 = buildSegment("10 people arrived today.");
        List<WhisperSegmentDTO> cleaned1 = sanitizerService.sanitizeSegments(List.of(seg1));
        assertEquals(1, cleaned1.size());
        assertEquals("10 people arrived today.", cleaned1.get(0).getText());
        assertEquals(seg1.getStartTime(), cleaned1.get(0).getStartTime(), "startTime không bị thay đổi vì 10 là số lượng người");

        WhisperSegmentDTO seg2 = buildSegment("5000 yen is cheap.");
        List<WhisperSegmentDTO> cleaned2 = sanitizerService.sanitizeSegments(List.of(seg2));
        assertEquals(1, cleaned2.size());
        assertEquals("5000 yen is cheap.", cleaned2.get(0).getText());
    }

    private WhisperSegmentDTO buildSegment(String text) {
        return WhisperSegmentDTO.builder()
                .sentenceIndex(0)
                .startTime(1.0)
                .endTime(3.0)
                .text(text)
                .build();
    }
}
