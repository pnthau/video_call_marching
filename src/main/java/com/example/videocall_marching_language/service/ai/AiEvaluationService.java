package com.example.videocall_marching_language.service.ai;

import com.example.videocall_marching_language.dto.SpeechEvaluationResponse;
import com.example.videocall_marching_language.dto.tts.TtsSegmentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.entity.User;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEvaluationService {

    private final AiProxyService aiProxyService;
    private final IUserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long getCurrentUserId() {
        if (SecurityContextHolder.getContext().getAuthentication() == null) return null;
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).map(User::getId).orElse(null);
    }

    public String generateMaskedScript(String originalScript) {
        try {
            String prompt = "Đóng vai một chuyên gia ngôn ngữ, hãy trích xuất khoảng 30% các từ khóa quan trọng nhất " +
                    "(ưu tiên động từ chính, danh từ, tính từ mang ý nghĩa cốt lõi) từ đoạn văn sau để làm bài tập điền từ. " +
                    "YÊU CẦU BẮT BUỘC: Chỉ trả về một mảng JSON chứa các từ (ví dụ: [\"word1\", \"word2\"]), không được kèm theo bất kỳ văn bản giải thích nào khác.\n\n" +
                    "Đoạn văn: " + originalScript;

            String responseText = aiProxyService.chat(getCurrentUserId(), prompt);
            
            int start = responseText.indexOf('[');
            int end = responseText.lastIndexOf(']');
            if (start != -1 && end != -1 && start < end) {
                responseText = responseText.substring(start, end + 1);
            }

            JsonNode arrayNode = objectMapper.readTree(responseText);
            
            List<String> keywordsToMask = new ArrayList<>();
            if (arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    keywordsToMask.add(node.asText().trim());
                }
            }

            String maskedScript = originalScript;
            for (String keyword : keywordsToMask) {
                String regex = "(?i)\\b" + keyword + "\\b";
                maskedScript = maskedScript.replaceAll(regex, "____");
            }

            return maskedScript;

        } catch (Exception e) {
            log.error("Error calling AI API for masking: ", e);
            return simpleFallbackMasking(originalScript);
        }
    }

    private String simpleFallbackMasking(String script) {
        String[] words = script.split("\\s+");
        StringBuilder masked = new StringBuilder();
        int count = 0;
        for (String word : words) {
            String cleanWord = word.replaceAll("[^a-zA-Z]", "");
            if (cleanWord.length() > 4 && count % 3 == 0) {
                masked.append("____").append(" ");
            } else {
                masked.append(word).append(" ");
            }
            count++;
        }
        return masked.toString().trim();
    }

    public SpeechEvaluationResponse evaluateSpeech(String originalSentence, String userSentence, int phase) {
        return evaluateSpeech(originalSentence, userSentence, phase, "ja");
    }

    public SpeechEvaluationResponse evaluateSpeech(String originalSentence, String userSentence, int phase, String languageCode) {
        String targetLang = (languageCode != null && !languageCode.isBlank()) ? languageCode.trim() : "ja";
        String targetLangCode = switch (targetLang.toLowerCase()) {
            case "ja", "ja-jp" -> "ja-JP";
            case "en", "en-us" -> "en-US";
            case "ko", "ko-kr" -> "ko-KR";
            case "zh", "zh-cn" -> "zh-CN";
            case "fr", "fr-fr" -> "fr-FR";
            case "es", "es-es" -> "es-ES";
            default -> targetLang;
        };

        String prompt = "";
        long startTime = System.currentTimeMillis();

        try {
            String phaseContext = switch (phase) {
                case 1 -> "Người dùng đang ở Giai đoạn 1 (Nghe & Lặp lại từng câu). Hãy đánh giá chặt chẽ về phát âm và ngữ pháp.";
                case 2 -> "Người dùng đang ở Giai đoạn 2 (Điền từ khuyết). Hãy đánh giá xem người dùng có nhớ đúng từ khóa và phát âm chính xác không.";
                case 3 -> "Người dùng đang ở Giai đoạn 3 (Nói tự do). Hãy đánh giá tổng thể về nội dung, ngữ pháp, và sự lưu loát.";
                default -> "Hãy đánh giá câu nói của người dùng.";
            };

            prompt = "Bạn là một giáo viên ngôn ngữ chuyên nghiệp. " + phaseContext + "\n\n" +
                    "Ngôn ngữ đang học: " + targetLangCode + "\n" +
                    "Câu gốc (chuẩn): \"" + originalSentence + "\"\n" +
                    "Câu người dùng nói: \"" + userSentence + "\"\n\n" +
                    "QUY TẮC PHÂN LOẠI 3 TRẠNG THÁI (status):\n" +
                    "1. \"PASS\" (Đúng): Người dùng đọc chuẩn xác hoặc phát âm đúng đầy đủ câu, đạt yêu cầu. Đặt \"isCorrect\": true.\n" +
                    "2. \"NEAR\" (Gần đúng): Người dùng đọc đúng được phần lớn câu, nắm được ý hoặc các từ chính nhưng còn vấp 1 âm nhỏ, hoặc thiếu/nhầm 1 trợ từ/từ phụ không làm sai nghĩa cơ bản. Đặt \"isCorrect\": false.\n" +
                    "3. \"FAIL\" (Sai nhiều): Người dùng nói sai nhiều, thiếu nhiều từ khóa chính, sai cấu trúc câu, chỉ đọc 1 đoạn ngắn rời rạc hoặc phát âm sai lệch hẳn. Đặt \"isCorrect\": false.\n\n" +
                    "QUY TẮC BẮT BUỘC CHO MẢNG TTS (ttsSegments) - PHÂN TÁCH SONG NGỮ LUÂN PHIÊN:\n" +
                    "Hệ thống sử dụng công nghệ Edge-TTS đa giọng: Giọng tiếng Việt ('vi-VN') và Giọng ngoại ngữ ('" + targetLangCode + "').\n" +
                    "Để phát âm chuẩn xác, không bị biến dạng giọng đọc và phản hồi tự nhiên như giáo viên bản xứ:\n" +
                    "1. TUYỆT ĐỐI KHÔNG để chữ tiếng nước ngoài (Nhật/Anh...) nằm chung trong segment tiếng Việt 'vi-VN'.\n" +
                    "2. BẮT BUỘC phân tách các từ chỉ dẫn tiếng Việt và từ ngoại ngữ thành các segment xen kẽ nhau.\n\n" +
                    "- Khi người dùng đọc SAI hoặc GẦN ĐÚNG (status = 'NEAR' hoặc 'FAIL'):\n" +
                    "  Hãy chỉ ra chính xác từ người dùng đọc nhầm, từ bị thiếu, hoặc câu mẫu chuẩn theo cấu trúc luân phiên:\n" +
                    "  + Segment dẫn (lang: 'vi-VN'): \"Bạn đã phát âm nhầm \" hoặc \"Bạn đã đọc thiếu từ \"\n" +
                    "  + Segment từ chuẩn (lang: '" + targetLangCode + "'): [Từ chuẩn trong câu gốc]\n" +
                    "  + Segment nối (lang: 'vi-VN'): \" thành \" (nếu phát âm nhầm)\n" +
                    "  + Segment từ sai (lang: '" + targetLangCode + "'): [Từ hoặc âm mà người dùng đã đọc sai]\n" +
                    "  + Segment chuyển tiếp (lang: 'vi-VN'): \". Hãy chú ý nhé. Hãy nghe lại câu mẫu sau đây:\"\n" +
                    "  + Segment câu mẫu hoàn chỉnh (lang: '" + targetLangCode + "'): \"" + originalSentence + "\"\n\n" +
                    "- Khi người dùng đọc ĐÚNG (status = 'PASS'):\n" +
                    "  Chỉ cần 1 segment khen ngợi súc tích:\n" +
                    "  + Segment 1 (lang: 'vi-VN'): \"Xuất sắc! Bạn đã phát âm rất chuẩn xác.\"\n\n" +
                    "VÍ DỤ MẪU OUTPUT KHI PHÁT ÂM NHẦM:\n" +
                    "{\n" +
                    "  \"isCorrect\": false,\n" +
                    "  \"status\": \"NEAR\",\n" +
                    "  \"correctedSentence\": \"" + originalSentence + "\",\n" +
                    "  \"errors\": [\"Phát âm nhầm 'ギョーザ' thành 'ぎょうや'\"],\n" +
                    "  \"suggestion\": \"Bạn đã phát âm nhầm 'ギョーザ' thành 'ぎょうや'. Hãy chú ý nghe câu mẫu nhé.\",\n" +
                    "  \"ttsSegments\": [\n" +
                    "    { \"lang\": \"vi-VN\", \"text\": \"Bạn đã phát âm nhầm \" },\n" +
                    "    { \"lang\": \"" + targetLangCode + "\", \"text\": \"ギョーザ\" },\n" +
                    "    { \"lang\": \"vi-VN\", \"text\": \" thành \" },\n" +
                    "    { \"lang\": \"" + targetLangCode + "\", \"text\": \"ぎょうや\" },\n" +
                    "    { \"lang\": \"vi-VN\", \"text\": \". Hãy chú ý nhé. Hãy nghe lại câu mẫu:\" },\n" +
                    "    { \"lang\": \"" + targetLangCode + "\", \"text\": \"" + originalSentence + "\" }\n" +
                    "  ]\n" +
                    "}\n\n" +
                    "YÊU CẦU BẮT BUỘC: Chỉ trả về JSON duy nhất với cấu trúc sau, KHÔNG dùng markdown ```json hay giải thích thêm ngoài JSON:\n" +
                    "{\n" +
                    "  \"isCorrect\": true/false,\n" +
                    "  \"status\": \"PASS\" hoặc \"NEAR\" hoặc \"FAIL\",\n" +
                    "  \"correctedSentence\": \"câu chuẩn đã sửa\",\n" +
                    "  \"errors\": [\"mô tả lỗi 1\", \"mô tả lỗi 2\"],\n" +
                    "  \"suggestion\": \"lời khuyên ngắn gọn để hiển thị lên màn hình (tiếng Việt)\",\n" +
                    "  \"ttsSegments\": [\n" +
                    "    { \"text\": \"...\", \"lang\": \"vi-VN\" },\n" +
                    "    { \"text\": \"...\", \"lang\": \"" + targetLangCode + "\" }\n" +
                    "  ]\n" +
                    "}";

            log.info("===> [AI EVALUATION PROMPT SENT]:\n{}", prompt);
            String responseText = aiProxyService.chat(getCurrentUserId(), prompt);
            log.info("<=== [AI EVALUATION RAW RESPONSE RECEIVED]:\n{}", responseText);

            String jsonText = responseText;
            int start = jsonText.indexOf('{');
            int end = jsonText.lastIndexOf('}');
            if (start != -1 && end != -1 && start < end) {
                jsonText = jsonText.substring(start, end + 1);
            }
            
            JsonNode resultNode = objectMapper.readTree(jsonText);

            List<String> errors = new ArrayList<>();
            JsonNode errorsNode = resultNode.path("errors");
            if (errorsNode.isArray()) {
                for (JsonNode e : errorsNode) {
                    errors.add(unescapeHtmlSimple(e.asText()));
                }
            }

            List<TtsSegmentResponse> rawTtsSegments = new ArrayList<>();
            JsonNode ttsNode = resultNode.path("ttsSegments");
            if (ttsNode.isArray()) {
                for (JsonNode tts : ttsNode) {
                    String segText = tts.path("text").asText("");
                    String segLang = tts.path("lang").asText("");
                    if (!segText.isBlank()) {
                        rawTtsSegments.add(TtsSegmentResponse.builder()
                                .text(unescapeHtmlSimple(segText))
                                .lang(segLang.isBlank() ? targetLangCode : segLang)
                                .build());
                    }
                }
            }

            // Hậu xử lý TTS: Đảm bảo các từ tiếng Nhật/ngoại ngữ trong segment vi-VN được tách riêng chuẩn xác
            List<TtsSegmentResponse> sanitizedSegments = sanitizeTtsSegments(rawTtsSegments, targetLangCode, originalSentence);

            boolean isCorrectVal = resultNode.path("isCorrect").asBoolean(false);
            String status = resultNode.path("status").asText("").trim().toUpperCase();
            if (status.isEmpty()) {
                status = isCorrectVal ? "PASS" : "FAIL";
            }

            String suggestion = unescapeHtmlSimple(resultNode.path("suggestion").asText(""));
            String correctedSentence = unescapeHtmlSimple(resultNode.path("correctedSentence").asText(originalSentence));

            long duration = System.currentTimeMillis() - startTime;
            log.info("<=== [AI EVALUATION PARSED] status={}, isCorrect={}, duration={}ms, segmentsCount={}",
                    status, isCorrectVal, duration, sanitizedSegments.size());

            return SpeechEvaluationResponse.builder()
                    .isCorrect(isCorrectVal)
                    .originalSentence(originalSentence)
                    .userSentence(userSentence)
                    .correctedSentence(correctedSentence)
                    .errors(errors)
                    .suggestion(suggestion)
                    .status(status)
                    .ttsSegments(sanitizedSegments)
                    .rawAiResponse(responseText)
                    .aiPrompt(prompt)
                    .debugDetails("AI evaluation completed in " + duration + "ms. Provider: GEMINI.")
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Error calling AI API for evaluation ({}ms): ", duration, e);
            SpeechEvaluationResponse fallback = mockEvaluation(originalSentence, userSentence, targetLangCode);
            fallback.setRawAiResponse("EXCEPTION: " + e.getMessage() + (e.getCause() != null ? " | Cause: " + e.getCause().getMessage() : ""));
            fallback.setAiPrompt(prompt);
            fallback.setDebugDetails("Fallback mock evaluation used due to exception: " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Hậu xử lý ttsSegments: Nếu segment tiếng Việt (vi-VN) bị Gemini nhồi chữ tiếng Nhật,
     * tự động tách chữ tiếng Nhật ra thành segment ngôn ngữ targetLangCode (ja-JP) để giọng đọc đọc chuẩn xác.
     */
    private List<TtsSegmentResponse> sanitizeTtsSegments(
            List<TtsSegmentResponse> segments,
            String targetLangCode,
            String originalSentence) {

        List<TtsSegmentResponse> result = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            result.add(TtsSegmentResponse.builder()
                    .text("Hãy nghe và đọc lại theo câu mẫu nhé:")
                    .lang("vi-VN")
                    .build());
            result.add(TtsSegmentResponse.builder()
                    .text(originalSentence)
                    .lang(targetLangCode)
                    .build());
            return result;
        }

        boolean isJapanese = targetLangCode.toLowerCase().startsWith("ja");

        for (TtsSegmentResponse seg : segments) {
            if (seg == null || seg.getText() == null || seg.getText().isBlank()) {
                continue;
            }

            String text = unescapeHtmlSimple(seg.getText().trim());
            String rawLang = (seg.getLang() != null && !seg.getLang().isBlank()) ? seg.getLang().trim() : targetLangCode;
            String lang = normalizeLang(rawLang, targetLangCode);

            if (isJapanese && lang.equalsIgnoreCase("vi-VN") && containsJapanese(text)) {
                List<TtsSegmentResponse> splitList = splitMixedVietnameseJapanese(text, targetLangCode);
                result.addAll(splitList);
            } else {
                result.add(TtsSegmentResponse.builder()
                        .text(text)
                        .lang(lang)
                        .build());
            }
        }

        return result;
    }

    private String normalizeLang(String rawLang, String defaultTargetLang) {
        if (rawLang == null || rawLang.isBlank()) {
            return defaultTargetLang;
        }
        String clean = rawLang.trim().toLowerCase();
        if (clean.startsWith("vi")) return "vi-VN";
        if (clean.startsWith("ja")) return "ja-JP";
        if (clean.startsWith("en")) return "en-US";
        if (clean.startsWith("ko")) return "ko-KR";
        if (clean.startsWith("zh")) return "zh-CN";
        if (clean.startsWith("fr")) return "fr-FR";
        if (clean.startsWith("es")) return "es-ES";
        return rawLang.trim();
    }

    private boolean containsJapanese(String text) {
        if (text == null) return false;
        for (char c : text.toCharArray()) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) {
                return true;
            }
        }
        return false;
    }

    private List<TtsSegmentResponse> splitMixedVietnameseJapanese(String text, String jaLangCode) {
        List<TtsSegmentResponse> segments = new ArrayList<>();
        StringBuilder currentBuffer = new StringBuilder();
        Boolean currentIsJp = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            boolean isJpChar = (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS);

            if (currentIsJp == null) {
                currentIsJp = isJpChar;
                currentBuffer.append(c);
            } else if (isJpChar == currentIsJp) {
                currentBuffer.append(c);
            } else {
                String chunk = cleanChunk(currentBuffer.toString());
                if (!chunk.isBlank()) {
                    segments.add(TtsSegmentResponse.builder()
                            .text(chunk)
                            .lang(currentIsJp ? jaLangCode : "vi-VN")
                            .build());
                }
                currentBuffer = new StringBuilder();
                currentIsJp = isJpChar;
                currentBuffer.append(c);
            }
        }

        String lastChunk = cleanChunk(currentBuffer.toString());
        if (!lastChunk.isBlank()) {
            segments.add(TtsSegmentResponse.builder()
                    .text(lastChunk)
                    .lang((currentIsJp != null && currentIsJp) ? jaLangCode : "vi-VN")
                    .build());
        }

        return segments;
    }

    private String cleanChunk(String str) {
        if (str == null) return "";
        return str.replaceAll("^[\"“”«»'\\s]+|[\"“”«»'\\s]+$", "").trim();
    }

    private String unescapeHtmlSimple(String text) {
        if (text == null) return "";
        return text.replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    public String generateText(String prompt) {
        try {
            return aiProxyService.chat(getCurrentUserId(), prompt);
        } catch (Exception e) {
            log.error("Error calling AI API for text generation: ", e);
            return "{\"replyText\": \"Xin lỗi, có lỗi xảy ra.\", \"reading\": \"\", \"translation\": \"Xin lỗi, có lỗi xảy ra.\", \"feedback\": \"\", \"suggestedReply\": \"\"}";
        }
    }

    private SpeechEvaluationResponse mockEvaluation(String originalSentence, String userSentence, String targetLangCode) {
        boolean isCorrect = originalSentence.trim().equalsIgnoreCase(userSentence.trim());

        List<String> errors = new ArrayList<>();
        if (!isCorrect) {
            errors.add("Câu bạn nói chưa khớp với câu gốc. (Mock - lỗi kết nối AI)");
        }

        List<TtsSegmentResponse> ttsSegments = new ArrayList<>();
        if (!isCorrect) {
            ttsSegments.add(TtsSegmentResponse.builder().text("Câu bạn nói chưa khớp với câu gốc. Bạn hãy nghe lại câu mẫu nhé:").lang("vi-VN").build());
            ttsSegments.add(TtsSegmentResponse.builder().text(originalSentence).lang(targetLangCode).build());
        } else {
            ttsSegments.add(TtsSegmentResponse.builder().text("Tuyệt vời, bạn đọc rất chuẩn!").lang("vi-VN").build());
        }

        return SpeechEvaluationResponse.builder()
                .isCorrect(isCorrect)
                .originalSentence(originalSentence)
                .userSentence(userSentence)
                .correctedSentence(isCorrect ? originalSentence : originalSentence)
                .errors(errors)
                .suggestion(isCorrect ? "Tuyệt vời! Bạn đã nói đúng." : "Hãy thử lại, đọc chậm và rõ ràng hơn.")
                .status(isCorrect ? "PASS" : "FAIL")
                .ttsSegments(ttsSegments)
                .build();
    }
}
