package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.GeneratedLessonDTO;
import com.example.videocall_marching_language.dto.audiolesson.GeneratedSentenceDTO;
import com.example.videocall_marching_language.dto.audiolesson.WhisperSegmentDTO;
import com.example.videocall_marching_language.service.ai.AiProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiLessonGeneratorService {

    private final AiProxyService aiProxyService;
    private final WhisperSanitizerService whisperSanitizerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeneratedLessonDTO generateLessonContent(
            Long userId,
            List<WhisperSegmentDTO> segments,
            String language,
            String topicName) {

        // Chặn và làm sạch âm rác, tạp âm, nốt nhạc, thẻ hiệu ứng và ảo giác phụ đề từ Whisper trước khi gửi LLM
        List<WhisperSegmentDTO> cleanSegments = whisperSanitizerService.sanitizeSegments(segments);

        if (cleanSegments == null || cleanSegments.isEmpty()) {
            throw new IllegalArgumentException("Không phát hiện nội dung giọng nói hợp lệ trong file âm thanh (chỉ phát hiện âm thanh rác, khoảng lặng hoặc tạp âm). Vui lòng thử lại với file âm thanh rõ ràng hơn.");
        }

        StringBuilder segmentsText = new StringBuilder();
        for (WhisperSegmentDTO seg : cleanSegments) {
            segmentsText.append(String.format(
                    "[%d] (start: %.2fs, end: %.2fs): %s\n",
                    seg.getSentenceIndex(), seg.getStartTime(), seg.getEndTime(), seg.getText()
            ));
        }

        String prompt = buildPrompt(segmentsText.toString(), language, topicName);
        log.info("===> [AI LESSON GENERATOR] Gửi prompt tạo bài học tới AI, số câu hợp lệ: {}", cleanSegments.size());

        String aiResponse = aiProxyService.chat(userId, prompt);
        log.info("<=== [AI LESSON GENERATOR] Nhận phản hồi từ AI, độ dài: {}", aiResponse.length());

        return parseGeneratedLesson(aiResponse, cleanSegments);
    }

    String buildPrompt(String segmentsData, String language, String topicName) {
        boolean isEn = language != null && (
                "en".equalsIgnoreCase(language.trim()) ||
                "english".equalsIgnoreCase(language.trim()) ||
                language.trim().toLowerCase().startsWith("en")
        );

        if (isEn) {
            return buildEnglishPrompt(segmentsData, topicName);
        } else {
            return buildJapanesePrompt(segmentsData, topicName);
        }
    }

    String buildJapanesePrompt(String segmentsData, String topicName) {
        return """
        Bạn là chuyên gia sư phạm ngôn ngữ và thiết kế bài học tương tác cao cấp.
        Nhiệm vụ của bạn là nhận danh sách các câu được bóc tách từ file audio (kèm timestamps) và sinh ra bộ học liệu tương tác hoàn chỉnh bằng tiếng Việt.

        Chủ đề bài học: "%s"
        Ngôn ngữ mục tiêu: "Tiếng Nhật (ja)"

        Danh sách câu trích xuất từ audio:
        %s

        QUY TẮC BẮT BUỘC VỀ PHIÊN ÂM VÀ HỌC LIỆU:
        1. PHẦN 1 - PHIÊN ÂM (phonetic):
           - PHẢI PHIÊN ÂM CHÍNH XÁC THEO CÁCH PHÁT ÂM THỰC TẾ TRONG AUDIO.
           - ĐẶC BIỆT chú ý cách đọc số và biến thể âm trong tiếng Nhật:
             + Nếu audio đọc 17 là "juushichi" thì phiên âm BẮT BUỘC là "juushichi (じゅうしち)", TUYỆT ĐỐI KHÔNG tự động chuyển thành "juunana".
             + Tương tự: số 7 nếu đọc "shichi" thì ghi "shichi (しち)", số 4 nếu đọc "shi" thì ghi "shi (し)" hoặc "yon (よん)" theo đúng âm audio.
             + Cung cấp định dạng kết hợp dễ đọc: "Romaji (Hiragana)" ví dụ: "Juushichi-sai desu (じゅうしちさいです)".
        2. PHẦN 2 - ĐỀ BÀI THỬ THÁCH ĐẶT CÂU & MẢNH GHÉP TỪ VỰNG GỢI Ý (BẮT BUỘC):
           Quy trình tư duy 3 bước bắt buộc để đảm bảo đề bài thử thách và các mảnh ghép từ vựng KHỚP NHAU 100%%:
           - Bước 1: "challengeTargetSentence": Sáng tạo một CÂU TIẾNG NHẬT MẪU HOÀN TOÀN MỚI áp dụng cấu trúc ngữ pháp này vào tình huống thực tế (TUYỆT ĐỐI KHÔNG lặp lại câu gốc trong audio).
             Ví dụ: "日曜日にコンサートがあります。"
           - Bước 2: "sentenceChallengePrompt": Đề bài thử thách bằng tiếng Việt dịch từ câu mẫu trên.
             Ví dụ: "Thử thách: Đặt câu thông báo rằng vào Chủ Nhật có một buổi hòa nhạc (áp dụng cấu trúc 〜があります)."
           - Bước 3: "keyWordsJson": ĐÂY LÀ CÁC MẢNH GHÉP TỪ VỰNG ĐƯỢC BÓC TÁCH TRỰC TIẾP TỪ CHÍNH "challengeTargetSentence" Ở BƯỚC 1 (TUYỆT ĐỐI KHÔNG LẤY TỪ CÂU GỐC TRONG AUDIO).
             Bạn PHẢI cung cấp đúng 3 đến 5 từ vựng then chốt cấu thành nên câu trả lời của thử thách, để người học chỉ việc nhặt các từ này ghép lại thành câu mới.
             Ví dụ: Với câu thử thách "vào Chủ Nhật có một buổi hòa nhạc", keyWordsJson BẮT BUỘC phải là:
             [
               {"kanji": "日曜日", "hiragana": "にちようび", "romaji": "nichiyoubi", "meaning": "Chủ Nhật"},
               {"kanji": "コンサート", "hiragana": "こんさーと", "romaji": "konsaato", "meaning": "buổi hòa nhạc"},
               {"kanji": "あります", "hiragana": "あります", "romaji": "arimasu", "meaning": "có / diễn ra"}
             ]
             Mỗi từ vựng bắt buộc có đủ 4 trường: kanji, hiragana, romaji, meaning.

        YÊU CẦU ĐẦU RA:
        Chỉ trả về DUY NHẤT một chuỗi JSON hợp lệ (không kèm markdown ```json ... ``` hoặc bất kỳ text nào khác bên ngoài), tuân theo đúng cấu trúc JSON sau:
        {
          "lessonTitle": "Tiêu đề bài học hay và súc tích bằng tiếng Việt",
          "sentences": [
            {
              "sentenceIndex": 0,
              "startTime": 0.0,
              "endTime": 2.5,
              "originalText": "Câu gốc chính xác theo audio",
              "phonetic": "Romaji (Hiragana) chuẩn xác theo âm audio thực tế",
              "vietnameseMeaning": "Bản dịch tiếng Việt tự nhiên và sát nghĩa",
              "grammarPoint": "Tên điểm ngữ pháp nổi bật của câu (ví dụ: Thể Te + kara, Cấu trúc câu điều kiện...)",
              "explanation": "Giải thích ngữ pháp ngắn gọn, dễ hiểu bằng tiếng Việt (1-2 câu)",
              "formula": "Công thức ngữ pháp (ví dụ: V-te + kara, Danh từ + です)",
              "challengeTargetSentence": "日曜日にコンサートがあります。",
              "sentenceChallengePrompt": "Thử thách: Đặt câu thông báo rằng vào Chủ Nhật có một buổi hòa nhạc (áp dụng cấu trúc 〜があります).",
              "keyWordsJson": "[{\\"kanji\\":\\"日曜日\\",\\"hiragana\\":\\"にちようび\\",\\"romaji\\":\\"nichiyoubi\\",\\"meaning\\":\\"Chủ Nhật\\"},{\\"kanji\\":\\"コンサート\\",\\"hiragana\\":\\"こんさーと\\",\\"romaji\\":\\"konsaato\\",\\"meaning\\":\\"buổi hòa nhạc\\"},{\\"kanji\\":\\"あります\\",\\"hiragana\\":\\"あります\\",\\"romaji\\":\\"arimasu\\",\\"meaning\\":\\"có / diễn ra\\"}]",
              "exerciseType": "LISTENING_FILL_BLANK",
              "question": "Câu đố điền khuyết với chỗ trống ___",
              "options": ["Đáp án A", "Đáp án B", "Đáp án C", "Đáp án D"],
              "correctAnswer": "Đáp án đúng chính xác",
              "exerciseExplanation": "Giải thích vì sao chọn đáp án đó"
            }
          ]
        }
        LƯU Ý:
        - Giữ nguyên số lượng câu và đúng thứ tự sentenceIndex, startTime, endTime như dữ liệu đầu vào.
        - Đảm bảo 4 đáp án trắc nghiệm options có 1 đáp án đúng và 3 phương án gây nhiễu hợp lý.
        """.formatted(topicName != null ? topicName : "Giao tiếp tiếng Nhật", segmentsData);
    }

    String buildEnglishPrompt(String segmentsData, String topicName) {
        return """
        Bạn là chuyên gia sư phạm ngôn ngữ và thiết kế bài học tiếng Anh tương tác cao cấp.
        Nhiệm vụ của bạn là nhận danh sách các câu tiếng Anh được bóc tách từ file audio (kèm timestamps) và sinh ra bộ học liệu tương tác hoàn chỉnh bằng tiếng Việt.

        Chủ đề bài học: "%s"
        Ngôn ngữ mục tiêu: "Tiếng Anh (English / en)"

        Danh sách câu trích xuất từ audio:
        %s

        QUY TẮC BẮT BUỘC VỀ PHIÊN ÂM VÀ HỌC LIỆU DÀNH CHO TIẾNG ANH:
        1. PHẦN 1 - PHIÊN ÂM (phonetic):
           - BẮT BUỘC dùng Ký hiệu phiên âm quốc tế IPA chuẩn (International Phonetic Alphabet) cho toàn bộ câu (ví dụ: "/aɪ lʌv ˈlɜːnɪŋ ˈɪŋɡlɪʃ/").
           - TUYỆT ĐỐI KHÔNG sinh Romaji, Hiragana, Katakana hay bất kỳ chữ tượng hình/âm đọc tiếng Nhật nào trong trường phonetic của tiếng Anh.
        2. PHẦN 2 - ĐỀ BÀI THỬ THÁCH ĐẶT CÂU & MẢNH GHÉP TỪ VỰNG GỢI Ý (BẮT BUỘC):
           Quy trình tư duy 3 bước bắt buộc để đảm bảo đề bài thử thách và các mảnh ghép từ vựng KHỚP NHAU 100%%:
           - Bước 1: "challengeTargetSentence": Sáng tạo một CÂU TIẾNG ANH MẪU HOÀN TOÀN MỚI áp dụng cấu trúc ngữ pháp này vào tình huống thực tế (TUYỆT ĐỐI KHÔNG lặp lại câu gốc trong audio).
             Ví dụ: "She is interested in learning foreign languages."
           - Bước 2: "sentenceChallengePrompt": Đề bài thử thách bằng tiếng Việt dịch từ câu mẫu trên.
             Ví dụ: "Thử thách: Đặt câu nói cô ấy rất quan tâm/thích thú việc học ngoại ngữ (áp dụng cấu trúc be interested in)."
           - Bước 3: "keyWordsJson": ĐÂY LÀ CÁC MẢNH GHÉP TỪ VỰNG TIẾNG ANH ĐƯỢC BÓC TÁCH TRỰC TIẾP TỪ CHÍNH "challengeTargetSentence" Ở BƯỚC 1 (TUYỆT ĐỐI KHÔNG LẤY TỪ CÂU GỐC TRONG AUDIO).
             Bạn PHẢI cung cấp đúng 3 đến 5 từ vựng then chốt cấu thành nên câu trả lời của thử thách, để người học chỉ việc nhặt các từ này ghép lại thành câu mới.
             Schema keyWordsJson BẮT BUỘC có đủ 3 trường: "word", "ipa", "meaning" (TUYỆT ĐỐI KHÔNG chứa cấu trúc từ tiếng Nhật):
             [
               {"word": "interested", "ipa": "/ˈɪn.trɪs.tɪd/", "meaning": "quan tâm, thích thú"},
               {"word": "foreign", "ipa": "/ˈfɒr.ən/", "meaning": "nước ngoài"},
               {"word": "language", "ipa": "/ˈlæŋ.ɡwɪdʒ/", "meaning": "ngôn ngữ"}
             ]
             Mỗi từ vựng bắt buộc có đủ 3 trường: word, ipa, meaning.
        3. PHẦN 3 - ĐIỂM NGỮ PHÁP TIẾNG ANH (grammarPoint, formula, explanation):
           - grammarPoint: Tên cấu trúc ngữ pháp tiếng Anh rõ ràng (ví dụ: Present Perfect, Conditional Sentence Type 1, Adjective + Preposition...).
           - formula: Công thức ngữ pháp tiếng Anh chuẩn (ví dụ: S + be + interested in + V-ing/Noun, S + have/has + V3/ed).
           - explanation: Giải thích ngữ pháp ngắn gọn, dễ hiểu bằng tiếng Việt (1-2 câu).
        4. PHẦN 4 - BÀI TẬP TRẮC NGHIỆM ĐIỀN KHUYẾT (LISTENING_FILL_BLANK):
           - question: Câu tiếng Anh có chỗ trống điền khuyết ___
           - options: Đúng 4 lựa chọn tiếng Anh (1 đáp án đúng và 3 phương án gây nhiễu hợp lý).
           - correctAnswer: Đáp án đúng chính xác.
           - exerciseExplanation: Giải thích bằng tiếng Việt vì sao chọn đáp án đó.

        YÊU CẦU ĐẦU RA:
        Chỉ trả về DUY NHẤT một chuỗi JSON hợp lệ (không kèm markdown ```json ... ``` hoặc bất kỳ text nào khác bên ngoài), tuân theo đúng cấu trúc JSON sau:
        {
          "lessonTitle": "Tiêu đề bài học hay và súc tích bằng tiếng Việt",
          "sentences": [
            {
              "sentenceIndex": 0,
              "startTime": 0.0,
              "endTime": 2.5,
              "originalText": "Câu gốc chính xác theo audio",
              "phonetic": "/aɪ lʌv ˈlɜːnɪŋ ˈɪŋɡlɪʃ/",
              "vietnameseMeaning": "Bản dịch tiếng Việt tự nhiên và sát nghĩa",
              "grammarPoint": "Tên điểm ngữ pháp tiếng Anh (ví dụ: Gerund as Object...)",
              "explanation": "Giải thích ngữ pháp ngắn gọn, dễ hiểu bằng tiếng Việt (1-2 câu)",
              "formula": "Công thức ngữ pháp (ví dụ: S + love + V-ing)",
              "challengeTargetSentence": "She is interested in learning foreign languages.",
              "sentenceChallengePrompt": "Thử thách: Đặt câu nói cô ấy rất quan tâm đến việc học ngoại ngữ (áp dụng cấu trúc be interested in).",
              "keyWordsJson": "[{\\"word\\":\\"interested\\",\\"ipa\\":\\"/ˈɪn.trɪs.tɪd/\\",\\"meaning\\":\\"quan tâm, thích thú\\"},{\\"word\\":\\"foreign\\",\\"ipa\\":\\"/ˈfɒr.ən/\\",\\"meaning\\":\\"nước ngoài\\"},{\\"word\\":\\"language\\",\\"ipa\\":\\"/ˈlæŋ.ɡwɪdʒ/\\",\\"meaning\\":\\"ngôn ngữ\\"}]",
              "exerciseType": "LISTENING_FILL_BLANK",
              "question": "She is interested ___ learning foreign languages.",
              "options": ["in", "on", "at", "for"],
              "correctAnswer": "in",
              "exerciseExplanation": "Cấu trúc be interested đi kèm giới từ in."
            }
          ]
        }
        LƯU Ý:
        - Giữ nguyên số lượng câu và đúng thứ tự sentenceIndex, startTime, endTime như dữ liệu đầu vào.
        - Đảm bảo 4 đáp án trắc nghiệm options có 1 đáp án đúng và 3 phương án gây nhiễu hợp lý.
        """.formatted(topicName != null ? topicName : "Giao tiếp tiếng Anh", segmentsData);
    }

    private GeneratedLessonDTO parseGeneratedLesson(String jsonText, List<WhisperSegmentDTO> originalSegments) {
        try {
            String cleanJson = jsonText.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substring(3);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            cleanJson = cleanJson.trim();

            GeneratedLessonDTO dto = objectMapper.readValue(cleanJson, GeneratedLessonDTO.class);

            // Đảm bảo timestamps từ Whisper không bị LLM làm lệch
            if (dto.getSentences() != null && originalSegments != null) {
                for (int i = 0; i < dto.getSentences().size(); i++) {
                    GeneratedSentenceDTO s = dto.getSentences().get(i);
                    if (i < originalSegments.size()) {
                        WhisperSegmentDTO orig = originalSegments.get(i);
                        s.setStartTime(orig.getStartTime());
                        s.setEndTime(orig.getEndTime());
                        if (s.getOriginalText() == null || s.getOriginalText().isBlank()) {
                            s.setOriginalText(orig.getText());
                        }
                    }
                }
            }

            return dto;

        } catch (Exception e) {
            log.warn("Không thể parse JSON từ AI, tạo fallback từ dữ liệu gốc: {}", e.getMessage());
            return buildFallbackLesson(originalSegments);
        }
    }

    private GeneratedLessonDTO buildFallbackLesson(List<WhisperSegmentDTO> segments) {
        List<GeneratedSentenceDTO> list = new ArrayList<>();
        for (WhisperSegmentDTO s : segments) {
            list.add(GeneratedSentenceDTO.builder()
                    .sentenceIndex(s.getSentenceIndex())
                    .startTime(s.getStartTime())
                    .endTime(s.getEndTime())
                    .originalText(s.getText())
                    .phonetic(s.getText())
                    .vietnameseMeaning("Đang cập nhật bản dịch...")
                    .grammarPoint("Mẫu câu giao tiếp")
                    .explanation("Luyện tập ghi nhớ câu này.")
                    .formula("")
                    .keyWordsJson("[]")
                    .sentenceChallengePrompt("Hãy gõ lại câu này để luyện ghi nhớ: " + s.getText())
                    .exerciseType("LISTENING_FILL_BLANK")
                    .question("Nghe và chọn câu đúng: ___")
                    .options(List.of(s.getText(), "Không nghe rõ", "Câu khác", "None"))
                    .correctAnswer(s.getText())
                    .exerciseExplanation("Chọn đúng câu bạn vừa nghe.")
                    .build());
        }

        return GeneratedLessonDTO.builder()
                .lessonTitle("Bài học Audio Tương tác")
                .sentences(list)
                .build();
    }
}
