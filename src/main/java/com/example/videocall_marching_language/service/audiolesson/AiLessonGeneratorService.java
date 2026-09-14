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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeneratedLessonDTO generateLessonContent(
            Long userId,
            List<WhisperSegmentDTO> segments,
            String language,
            String topicName) {

        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("Danh sách câu từ Whisper không được rỗng");
        }

        StringBuilder segmentsText = new StringBuilder();
        for (WhisperSegmentDTO seg : segments) {
            segmentsText.append(String.format(
                    "[%d] (start: %.2fs, end: %.2fs): %s\n",
                    seg.getSentenceIndex(), seg.getStartTime(), seg.getEndTime(), seg.getText()
            ));
        }

        String prompt = buildPrompt(segmentsText.toString(), language, topicName);
        log.info("===> [AI LESSON GENERATOR] Gửi prompt tạo bài học tới AI, số câu: {}", segments.size());

        String aiResponse = aiProxyService.chat(userId, prompt);
        log.info("<=== [AI LESSON GENERATOR] Nhận phản hồi từ AI, độ dài: {}", aiResponse.length());

        return parseGeneratedLesson(aiResponse, segments);
    }

    private String buildPrompt(String segmentsData, String language, String topicName) {
        return """                                                                                                                                                               
        Bạn là chuyên gia sư phạm ngôn ngữ. Hãy tạo bộ học liệu tương tác từ danh sách câu audio dưới đây.                                                                       
                                                                                                                                                                                 
        Chủ đề: "%s" | Ngôn ngữ: "%s"                                                                                                                                            
                                                                                                                                                                                 
        Dữ liệu câu audio:                                                                                                                                                       
        %s                                                                                                                                                                       
                                                                                                                                                                                 
        QUY TẮC BẮT BUỘC:                                                                                                                                                        
        1. phonetic: Phiên âm chính xác theo audio thực tế (chú ý số/biến âm), định dạng "Romaji (Hiragana)".                                                                    
        2. Thử thách đặt câu (phải khớp nhau hoàn toàn):                                                                                                                         
           - challengeTargetSentence: Tạo 1 câu mẫu mới áp dụng ngữ pháp của câu (không lặp lại câu gốc audio).                                                                  
           - sentenceChallengePrompt: Đề bài tiếng Việt yêu cầu người học đặt câu mẫu trên.                                                                                      
           - keyWordsJson: Chuỗi JSON chứa 3-5 mảnh ghép từ vựng bóc từ chính challengeTargetSentence, mỗi từ đủ 4 trường: kanji, hiragana, romaji, meaning.                     
        3. Trắc nghiệm: 4 options (1 đúng, 3 nhiễu hợp lý), giữ nguyên số lượng câu và đúng thứ tự sentenceIndex, startTime, endTime.                                            
                                                                                                                                                                                 
        YÊU CẦU ĐẦU RA:                                                                                                                                                          
        Chỉ trả về DUY NHẤT một chuỗi JSON hợp lệ (không kèm markdown ```json ... ```):                                                                                          
        {                                                                                                                                                                        
          "lessonTitle": "Tiêu đề bài học súc tích bằng tiếng Việt",                                                                                                             
          "sentences": [                                                                                                                                                         
            {                                                                                                                                                                    
              "sentenceIndex": 0,                                                                                                                                                
              "startTime": 0.0,                                                                                                                                                  
              "endTime": 2.5,                                                                                                                                                    
              "originalText": "Câu gốc từ audio",                                                                                                                                
              "phonetic": "Romaji (Hiragana)",                                                                                                                                   
              "vietnameseMeaning": "Nghĩa tiếng Việt",                                                                                                                           
              "grammarPoint": "Tên điểm ngữ pháp",                                                                                                                               
              "explanation": "Giải thích ngữ pháp ngắn gọn (1-2 câu)",                                                                                                           
              "formula": "Công thức ngữ pháp",                                                                                                                                   
              "challengeTargetSentence": "Câu mẫu mới áp dụng ngữ pháp",                                                                                                         
              "sentenceChallengePrompt": "Thử thách: Đặt câu...",                                                                                                                
              "keyWordsJson": "[{\\"kanji\\":\\"日曜日\\",\\"hiragana\\":\\"にちようび\\",\\"romaji\\":\\"nichiyoubi\\",\\"meaning\\":\\"Chủ Nhật\\"}]",                         
              "exerciseType": "LISTENING_FILL_BLANK",                                                                                                                            
              "question": "Câu đố có chỗ trống _",                                                                                                                             
              "options": ["A", "B", "C", "D"],                                                                                                                                   
              "correctAnswer": "Đáp án đúng",                                                                                                                                    
              "exerciseExplanation": "Giải thích vì sao chọn đáp án này"                                                                                                         
            }                                                                                                                                                                    
          ]                                                                                                                                                                      
        }                                                                                                                                                                        
        """.formatted(topicName != null ? topicName : "Giao tiếp tổng hợp", language != null ? language : "ja", segmentsData);
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
