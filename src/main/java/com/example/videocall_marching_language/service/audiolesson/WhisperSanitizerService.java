package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.audiolesson.WhisperSegmentDTO;
import com.example.videocall_marching_language.dto.audiolesson.WhisperWordDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service lọc và loại bỏ các âm thanh rác, tạp âm, ảo giác (hallucinations),
 * và phần giới thiệu/số thứ tự bài học (ví dụ "5.28", "1.", "Track 5:", "第1課")
 * sinh ra từ mô hình Whisper STT trước khi gửi sang LLM,
 * đồng thời điều chỉnh lại thời gian bắt đầu (startTime) và kết thúc (endTime) chuẩn xác cho câu nói thực tế.
 */
@Slf4j
@Service
public class WhisperSanitizerService {

    // 1. Ký hiệu âm nhạc đơn thuần (chỉ chứa nốt nhạc hoặc ký tự đệm)
    private static final Pattern MUSIC_SYMBOLS_ONLY_PATTERN =
            Pattern.compile("^[♪♫🎵🎶♩♬\\s\\.\\,\\!\\?\\-~…・]+$");

    // 2. Ký hiệu âm nhạc xuất hiện rải rác trong câu
    private static final Pattern INLINE_MUSIC_NOTES_PATTERN =
            Pattern.compile("[♪♫🎵🎶♩♬]+");

    // 3. Toàn bộ text nằm trọn trong ngoặc mô tả âm thanh không lời (ví dụ: [Music], (applause), （拍手）)
    private static final Pattern ENCLOSED_SOUND_TAG_PATTERN =
            Pattern.compile("^[\\(\\[\\{（【\\*\\<].*?[\\)\\]\\}）】\\*\\>]$");

    // Từ khóa âm thanh / phi ngôn ngữ bên trong ngoặc
    private static final Pattern SOUND_KEYWORDS_PATTERN =
            Pattern.compile("(?i).*(music|applause|laughter|laugh|giggle|cheering|silence|cough|sigh|groan|screaming|gasp|throat clearing|snicker|bgm|inaudible|chime|bell|sound effect|crying|sobbing|whisper|unintelligible|音楽|拍手|笑い|笑い声|笑|沈黙|歓声|ため息|咳|くしゃみ|効果音|BGM|雑音|無音|tiếng nhạc|vỗ tay|cười|tiếng cười|im lặng|ho).*",
                    Pattern.UNICODE_CASE);

    // 4. Các thẻ âm thanh xuất hiện lồng bên trong câu thoại hợp lệ
    private static final Pattern INLINE_SOUND_TAGS_PATTERN =
            Pattern.compile("(?i)\\[(music|applause|laughter|laugh|giggle|cheering|silence|cough|sigh|groan|screaming|gasp|bgm|inaudible)\\]" +
                    "|\\((music|applause|laughter|laugh|giggle|cheering|silence|cough|sigh|groan|screaming|gasp|bgm|inaudible)\\)" +
                    "|（(音楽|拍手|笑い|笑い声|笑|沈黙|歓声|ため息|咳|くしゃみ|効果音|BGM|雑音|無音)）" +
                    "|【(音楽|拍手|笑い|笑い声|笑|沈黙|歓声|ため息|咳|くしゃみ|効果音|BGM|雑音|無音)】" +
                    "|\\*(music|applause|laughter|cough|sigh)\\*",
                    Pattern.UNICODE_CASE);

    // 5. Các mẫu ảo giác phụ đề / YouTube mặc định của Whisper khi audio có khoảng lặng
    private static final List<Pattern> CANNED_HALLUCINATION_PATTERNS = List.of(
            // Tiếng Anh: Subtitle / YouTube credits
            Pattern.compile("(?i)\\b(thanks?(\\s+you(\\s+very\\s+much)?)?\\s+for\\s+(watching|listening))\\b"),
            Pattern.compile("(?i)\\b(please )?(don't forget to )?(like and )?subscribe( to (my|the) channel)?\\b"),
            Pattern.compile("(?i)\\b(see you (in the )?(next time|next video|soon|later))\\b"),
            Pattern.compile("(?i)\\b(subtitles? (by|created by|provided by))\\b"),
            Pattern.compile("(?i)\\b(transcribed|translated|captioned) by\\b"),
            Pattern.compile("(?i)\\b(amara\\.org|opensubtitles|ted\\.com)\\b"),
            Pattern.compile("(?i)\\b(all rights reserved|copyright)\\b"),
            Pattern.compile("(?i)^(bye|goodbye|bye[- ]bye|have a good day|have a good night)[\\s.!]?$"),

            // Tiếng Nhật: Subtitle / YouTube credits
            Pattern.compile("ご視聴(いただき)?ありがとう"),
            Pattern.compile("チャンネル登録"),
            Pattern.compile("高評価.*(お願い|よろしく)"),
            Pattern.compile("ご覧いただきありがとう"),
            Pattern.compile("最後まで(ご視聴|ご覧)"),
            Pattern.compile("^(字幕|翻訳|提供)\\s*[:：]"),

            // Tiếng Việt: Lời chào kết video YouTube
            Pattern.compile("(?i)cảm ơn (các bạn )?(đã|đang) (theo dõi|xem|lắng nghe)"),
            Pattern.compile("(?i)(hãy )?(nhớ )?(đăng ký|sub|subscribe) kênh"),
            Pattern.compile("(?i)(phụ đề|biên dịch) bởi"),
            Pattern.compile("(?i)nhấn chuông thông báo")
    );

    // 6. Lặp từ bất thường (Degeneration Loop) - lặp 1 từ 4 lần trở lên
    private static final Pattern REPEATED_WORD_PATTERN =
            Pattern.compile("(?i)\\b([a-zA-Z0-9\\p{L}]+)(?:[\\s,、\\.]+?\\1){3,}",
                    Pattern.UNICODE_CHARACTER_CLASS);

    // 7. Lặp ký tự đơn >= 5 lần (ví dụ: aaaaa, あああああ, wwwww)
    private static final Pattern REPEATED_CHAR_PATTERN =
            Pattern.compile("([a-zA-Z\\p{IsHiragana}\\p{IsKatakana}])\\1{4,}");

    // =========================================================================
    // CÁC PATTERN NHẬN DIỆN PHẦN GIỚI THIỆU ĐẦU CÂU (INTRO / TRACK PREFIXES)
    // =========================================================================

    // 8. Số thứ tự bài/track kèm dấu phân cách (5.28, 5.28., 1.2, 1., 2., 10., 1:, 1 - , 1、, ①, (1))
    private static final Pattern NUMERIC_INTRO_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(?:" +
            // Số thập phân như 5.28, 1.2.3, 01.2 (mã track/phần bài học)
            "(?:[\\(\\[（【]?\\s*[0-9０-９]+(?:\\.[0-9０-９]+)+[\\)\\]）】]?[-.:;、。：；，—–\\s]*)" +
            // Số nguyên có dấu phân cách rõ ràng theo sau (1., 1:, 1 -, 1、, 1), 1...)
            "|(?:[0-9０-９]+[-.:;、。：；，—–\\),，]+[\\s]*)" +
            // Số trong ngoặc đơn/vuông: (1), [1], （１）, 【２】
            "|(?:[\\(\\[（【][0-9０-９a-zA-Z]+[\\)\\]）】][-.:;、。：；，—–\\s]*)" +
            // Số khoanh tròn: ①, ②...
            "|[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳][-.:;、。：；，—–\\s]*" +
            ")"
    );

    // 9. Nhãn giới thiệu bài học, đoạn hội thoại, câu hỏi (EN, JA, VI)
    // Ví dụ: "Lesson 1.", "Track 5.28:", "Question 1.", "Dialogue 1:", "Section A."
    // Ví dụ: "第1課", "第１問", "1番、", "１番：", "一番、", "問題1.", "例1:", "会話1"
    // Ví dụ: "Bài 1:", "Câu 1.", "Phần 1:"
    private static final Pattern KEYWORD_INTRO_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(?:" +
            // EN
            "(?:track|lesson|unit|dialogue|conversation|section|part|question|exercise|item|number|no\\.?)\\s*(?:[0-9０-９]+(?:\\.[0-9０-９]+)?|[a-zA-Z]|one|two|three|four|five|six|seven|eight|nine|ten)[-.:;、。：；，—–\\s]*" +
            // JA
            "|第\\s*[0-9０-９一二三四五六七八九十]+\\s*(?:課|課目|問|話|章|節|部|条)[-.:;、。：；，—–\\s]*" +
            "|[0-9０-９一二三四五六七八九十]+\\s*番[-.:;、。：；，—–\\s]*" +
            "|(?:問題|会話|練習|例文)\\s*[0-9０-９A-Za-z一二三四五六七八九十]*[-.:;、。：；，—–\\s]*" +
            "|例(?:題)?[0-9０-９A-Za-z]*[-.:;、。：；，—–\\s]*" +
            "|トラック\\s*[0-9０-９]+(?:\\.[0-9０-９]+)?[-.:;、。：；，—–\\s]*" +
            // VI
            "|(?:bài(?: số)?|câu(?: hỏi| số)?|phần|mục)\\s*[0-9０-９A-Za-z]+[-.:;、。：；，—–\\s]*" +
            ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public record AdjustedTimeResult(double startTime, double endTime, List<WhisperWordDTO> remainingWords) {}

    /**
     * Lọc và làm sạch danh sách segments từ Whisper:
     * - Loại bỏ segment tạp âm, khoảng lặng, thẻ hiệu ứng, ảo giác phụ đề, vòng lặp lặp từ.
     * - Tách bỏ phần giới thiệu đầu câu (ví dụ: 5.28, 1., Lesson 1, 第1課, 1番、).
     * - Nếu segment chỉ chứa phần giới thiệu mà không có nội dung thoại -> Bỏ qua.
     * - Làm sạch các ký hiệu nhạc, thẻ inline trong các câu thoại hợp lệ.
     * - Cập nhật lại startTime và endTime đúng vị trí câu thoại thực tế (dựa vào word timestamps hoặc tỉ lệ dịch chuyển).
     * - Đánh số lại sentenceIndex tuần tự 0..N-1 để LLM xử lý chuẩn xác.
     *
     * @param rawSegments danh sách segment thô từ Whisper
     * @return danh sách segment sạch kèm timestamps chuẩn xác
     */
    public List<WhisperSegmentDTO> sanitizeSegments(List<WhisperSegmentDTO> rawSegments) {
        if (rawSegments == null || rawSegments.isEmpty()) {
            return Collections.emptyList();
        }

        List<WhisperSegmentDTO> cleanList = new ArrayList<>();
        int droppedCount = 0;

        for (WhisperSegmentDTO seg : rawSegments) {
            if (isNoiseSegment(seg)) {
                log.info("===> [WHISPER SANITIZER] Đã chặn âm rác/ảo giác: [{}] \"{}\" (start: {}s, end: {}s, noSpeechProb: {})",
                        seg.getSentenceIndex(), seg.getText(), seg.getStartTime(), seg.getEndTime(), seg.getNoSpeechProb());
                droppedCount++;
                continue;
            }

            String origText = seg.getText();
            int prefixLength = findIntroPrefixLength(origText);
            String textAfterPrefix = (prefixLength > 0 && prefixLength < origText.length())
                    ? origText.substring(prefixLength)
                    : (prefixLength >= origText.length() ? "" : origText);

            // Nếu toàn bộ segment chỉ là phần giới thiệu (ví dụ "5.28" hoặc "1." hoặc "Lesson 1") -> Bỏ qua
            if (textAfterPrefix.isBlank() || isPunctuationOnly(textAfterPrefix)) {
                log.info("===> [WHISPER SANITIZER] Bỏ qua segment chỉ chứa phần giới thiệu/số thứ tự: [{}] \"{}\"",
                        seg.getSentenceIndex(), origText);
                droppedCount++;
                continue;
            }

            // Làm sạch nội dung câu thoại (xóa nốt nhạc, thẻ âm thanh inline)
            String cleanedText = cleanText(textAfterPrefix);
            if (cleanedText.isEmpty() || isPunctuationOnly(cleanedText)) {
                log.info("===> [WHISPER SANITIZER] Bỏ qua segment sau khi làm sạch rỗng: [{}] \"{}\"",
                        seg.getSentenceIndex(), origText);
                droppedCount++;
                continue;
            }

            // Điều chỉnh lại startTime và endTime đúng câu
            AdjustedTimeResult timeResult = calculateAdjustedTimestamps(seg, prefixLength, origText);

            if (prefixLength > 0) {
                log.info("===> [WHISPER INTRO STRIPPED] [{}] \"{}\" -> \"{}\" (start: {}s -> {}s, end: {}s -> {}s)",
                        seg.getSentenceIndex(), origText.trim(), cleanedText,
                        seg.getStartTime(), timeResult.startTime(), seg.getEndTime(), timeResult.endTime());
            }

            cleanList.add(WhisperSegmentDTO.builder()
                    .sentenceIndex(cleanList.size()) // Đánh lại index tuần tự
                    .startTime(timeResult.startTime())
                    .endTime(timeResult.endTime())
                    .text(cleanedText)
                    .noSpeechProb(seg.getNoSpeechProb())
                    .avgLogprob(seg.getAvgLogprob())
                    .compressionRatio(seg.getCompressionRatio())
                    .words(timeResult.remainingWords())
                    .build());
        }

        log.info("WhisperSanitizer: Hoàn tất lọc segments. Giữ lại {}/{} câu hợp lệ (loại bỏ {} âm rác/ảo giác/intro)",
                cleanList.size(), rawSegments.size(), droppedCount);

        return cleanList;
    }

    /**
     * Tìm độ dài của phần giới thiệu ở đầu chuỗi (ví dụ: "5.28 ", "1. ", "Track 5: ", "第1課 ").
     * Có thể lặp nếu có nhiều nhãn liên tiếp (ví dụ: "Track 5. 1. Hello").
     */
    public int findIntroPrefixLength(String text) {
        if (text == null || text.isBlank()) return 0;

        int totalPrefixLength = 0;

        // Bỏ qua khoảng trắng đầu câu
        int leadingSpaces = 0;
        while (leadingSpaces < text.length() && Character.isWhitespace(text.charAt(leadingSpaces))) {
            leadingSpaces++;
        }
        totalPrefixLength += leadingSpaces;
        String current = text.substring(leadingSpaces);

        while (true) {
            Matcher mNum = NUMERIC_INTRO_PREFIX_PATTERN.matcher(current);
            if (mNum.find() && mNum.start() == 0) {
                int len = mNum.end();
                totalPrefixLength += len;
                current = current.substring(len);
                continue;
            }

            Matcher mKey = KEYWORD_INTRO_PREFIX_PATTERN.matcher(current);
            if (mKey.find() && mKey.start() == 0) {
                int len = mKey.end();
                totalPrefixLength += len;
                current = current.substring(len);
                continue;
            }

            break;
        }

        return totalPrefixLength;
    }

    /**
     * Tính toán thời gian bắt đầu (startTime) và kết thúc (endTime) chuẩn xác của câu
     * sau khi đã loại bỏ phần giới thiệu.
     */
    public AdjustedTimeResult calculateAdjustedTimestamps(WhisperSegmentDTO seg, int prefixLength, String originalText) {
        double originalStart = seg.getStartTime();
        double originalEnd = seg.getEndTime();

        if (prefixLength <= 0 || originalText == null || originalText.isEmpty()) {
            return new AdjustedTimeResult(originalStart, originalEnd, seg.getWords());
        }

        List<WhisperWordDTO> words = seg.getWords();
        if (words != null && !words.isEmpty()) {
            // Tìm từ nội dung đầu tiên sau prefix
            int charAccumulator = 0;
            int firstContentWordIdx = -1;
            int lastContentWordIdx = words.size() - 1;

            for (int i = 0; i < words.size(); i++) {
                WhisperWordDTO w = words.get(i);
                String wClean = w.getWord() != null ? w.getWord().trim() : "";
                if (wClean.isEmpty()) continue;

                int wordPos = originalText.indexOf(wClean, charAccumulator);
                if (wordPos == -1) {
                    wordPos = charAccumulator;
                }
                int wordEndPos = wordPos + wClean.length();
                charAccumulator = wordEndPos;

                if (wordEndPos > prefixLength) {
                    if (firstContentWordIdx == -1) {
                        firstContentWordIdx = i;
                    }
                    lastContentWordIdx = i;
                }
            }

            if (firstContentWordIdx != -1) {
                double newStart = words.get(firstContentWordIdx).getStartTime();
                double newEnd = words.get(lastContentWordIdx).getEndTime();
                if (newEnd <= newStart) {
                    newEnd = originalEnd;
                }
                List<WhisperWordDTO> subWords = words.subList(firstContentWordIdx, lastContentWordIdx + 1);
                return new AdjustedTimeResult(newStart, newEnd, subWords);
            }
        }

        // Fallback: Ước lượng dịch chuyển theo tỉ lệ ký tự của prefix
        double duration = originalEnd - originalStart;
        if (duration > 0 && originalText.length() > 0) {
            double ratio = (double) prefixLength / originalText.length();
            double shift = duration * Math.min(ratio, 0.85);
            double newStart = Math.round((originalStart + shift) * 100.0) / 100.0;
            if (newStart < originalEnd) {
                return new AdjustedTimeResult(newStart, originalEnd, null);
            }
        }

        return new AdjustedTimeResult(originalStart, originalEnd, null);
    }

    /**
     * Kiểm tra một segment có phải là âm rác / tạp âm / ảo giác hay không.
     */
    public boolean isNoiseSegment(WhisperSegmentDTO segment) {
        if (segment == null) return true;

        String text = segment.getText();
        if (text == null || text.isBlank()) return true;

        String trimmed = text.trim();

        // 1. Kiểm tra timestamp bất hợp lý
        if (segment.getEndTime() <= segment.getStartTime()) {
            return true;
        }
        double duration = segment.getEndTime() - segment.getStartTime();
        if (duration < 0.25 && trimmed.length() <= 2) {
            return true; // Click mic hoặc tiếng thở quá ngắn không thành từ
        }

        // 2. Kiểm tra chỉ số chất lượng từ Whisper (nếu có trong verbose_json)
        // no_speech_prob > 0.6: Chuẩn OpenAI Whisper xác định đoạn này không có giọng nói
        if (segment.getNoSpeechProb() != null && segment.getNoSpeechProb() > 0.6) {
            return true;
        }

        // compression_ratio > 2.4: Chuẩn OpenAI Whisper xác định mô hình bị kẹt lặp từ
        if (segment.getCompressionRatio() != null && segment.getCompressionRatio() > 2.4) {
            return true;
        }

        // avg_logprob quá thấp kết hợp no_speech_prob đáng ngờ
        if (segment.getAvgLogprob() != null && segment.getAvgLogprob() < -1.2
                && segment.getNoSpeechProb() != null && segment.getNoSpeechProb() > 0.35) {
            return true;
        }

        // 3. Kiểm tra ký hiệu âm nhạc hoặc dấu câu đơn thuần
        if (MUSIC_SYMBOLS_ONLY_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        if (isPunctuationOnly(trimmed)) {
            return true;
        }

        // 4. Kiểm tra thẻ mô tả âm thanh trong ngoặc (ví dụ: [Music], (applause), （拍手）)
        if (ENCLOSED_SOUND_TAG_PATTERN.matcher(trimmed).matches()) {
            if (SOUND_KEYWORDS_PATTERN.matcher(trimmed).matches() || trimmed.length() <= 15) {
                return true;
            }
        }

        // 5. Kiểm tra các mẫu ảo giác phụ đề / YouTube có sẵn
        for (Pattern cannedPattern : CANNED_HALLUCINATION_PATTERNS) {
            if (cannedPattern.matcher(trimmed).find()) {
                return true;
            }
        }

        // 6. Kiểm tra vòng lặp lặp từ (Whisper Degeneration Stuttering)
        if (REPEATED_WORD_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (REPEATED_CHAR_PATTERN.matcher(trimmed).find()) {
            return true;
        }

        // 7. Kiểm tra tỉ lệ từ duy nhất (Word diversity check cho các chuỗi lặp không có khoảng cách lớn)
        if (isRepetitiveLoop(trimmed)) {
            return true;
        }

        return false;
    }

    /**
     * Làm sạch các ký hiệu phi ngôn ngữ bên trong câu thoại hợp lệ.
     */
    public String cleanText(String rawText) {
        if (rawText == null) return "";

        String cleaned = rawText;

        // Xóa nốt nhạc
        cleaned = INLINE_MUSIC_NOTES_PATTERN.matcher(cleaned).replaceAll(" ");

        // Xóa các thẻ âm thanh inline như [Music], (applause), （拍手）
        cleaned = INLINE_SOUND_TAGS_PATTERN.matcher(cleaned).replaceAll(" ");

        // Xóa dấu lửng, gạch ngang hoặc ký tự thừa ở đầu câu
        cleaned = cleaned.replaceAll("^[-.:,;~…・。、\\s]+", "");

        // Chỉ xóa dấu gạch ngang, dấu ngã, dấu phẩy thừa ở cuối câu (giữ lại dấu kết câu . ! ? 。)
        cleaned = cleaned.replaceAll("[-~…・,、\\s]+$", "");
        cleaned = cleaned.replaceAll("\\.{2,}$", ""); // chỉ xóa dấu chấm lửng (từ 2 dấu chấm trở lên)

        // Gom khoảng trắng thừa
        cleaned = cleaned.replaceAll("\\s+", " ");

        return cleaned.trim();
    }

    /**
     * Kiểm tra chuỗi chỉ toàn dấu câu, ký tự đặc biệt hoặc khoảng trắng.
     */
    private boolean isPunctuationOnly(String text) {
        if (text == null || text.isBlank()) return true;
        // Bỏ hết ký tự dấu câu (\p{P}), ký hiệu (\p{S}) và khoảng trắng (\s)
        String stripped = text.replaceAll("[\\p{P}\\p{S}\\s]", "");
        return stripped.isEmpty();
    }

    /**
     * Kiểm tra chuỗi lặp từ bất thường:
     * Nếu câu có từ 5 từ trở lên nhưng số lượng từ duy nhất chiếm dưới 35% tổng số từ.
     */
    private boolean isRepetitiveLoop(String text) {
        String[] words = text.toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length >= 5) {
            Set<String> uniqueWords = new HashSet<>(Arrays.asList(words));
            double ratio = (double) uniqueWords.size() / words.length;
            if (ratio < 0.35) {
                return true;
            }
        }

        // Đối với tiếng Nhật / văn bản không có dấu cách: kiểm tra n-gram lặp
        if (text.length() >= 12 && words.length <= 2) {
            Set<Character> uniqueChars = new HashSet<>();
            for (char c : text.toCharArray()) {
                if (!Character.isWhitespace(c)) {
                    uniqueChars.add(c);
                }
            }
            if (!uniqueChars.isEmpty()) {
                double charRatio = (double) uniqueChars.size() / text.length();
                if (charRatio < 0.25) {
                    return true;
                }
            }
        }

        return false;
    }
}
