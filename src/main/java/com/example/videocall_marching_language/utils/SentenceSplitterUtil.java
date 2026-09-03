package com.example.videocall_marching_language.utils;

import com.example.videocall_marching_language.dto.SentenceRoleDTO;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SentenceSplitterUtil {
    public static List<String> splitIntoSentences(String text, String languageCode) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return sentences;
        }

        // 1. Khởi tạo BreakIterator theo ngôn ngữ của bài học
        Locale locale = Locale.forLanguageTag(languageCode);
        BreakIterator iterator = BreakIterator.getSentenceInstance(locale);
        iterator.setText(text);

        // 2. Vòng lặp duyệt qua và cắt từng câu
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    public static List<SentenceRoleDTO> assignRoles(List<String> sentences) {
        List<SentenceRoleDTO> result = new ArrayList<>();
        String currentRole = "Người nói"; // Vai mặc định

        // Regex tìm các dạng "A: ", "Khách hàng： " ở ngay đầu câu
        Pattern rolePattern = Pattern.compile("^([\\p{L}0-9_\\s]+)\\s*[:：]\\s*(.*)");

        for (String sentence : sentences) {
            Matcher matcher = rolePattern.matcher(sentence);
            if (matcher.find()) {
                // Cập nhật lại vai trò hiện tại (VD: lấy chữ "A")
                currentRole = matcher.group(1).trim();
                // Lấy phần nội dung thực sự (bỏ đi chữ "A: ")
                sentence = matcher.group(2).trim();
            }

            // Nếu câu không rỗng sau khi bỏ chữ "A: ", ta đưa vào mảng
            if (!sentence.isEmpty()) {
                result.add(SentenceRoleDTO.builder()
                        .role(currentRole)
                        .text(sentence)
                        .build());
            }
        }
        return result;
    }
}
