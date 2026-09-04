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

            if (!sentence.isEmpty()) {
                result.add(SentenceRoleDTO.builder()
                        .role(currentRole)
                        .text(sentence)
                        .build());
            }
        }
        return result;
    }

    public static List<SentenceRoleDTO> parseScriptLines(String content, String phoneticContent) {
        return parseScriptLines(content, phoneticContent, null);
    }

    public static List<SentenceRoleDTO> parseScriptLines(String content, String phoneticContent, String meaningContent) {
        List<SentenceRoleDTO> result = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return result;
        }

        Pattern rolePattern = Pattern.compile("^([\\p{L}0-9_\\s]+)\\s*[:：]\\s*(.*)");

        // 1. Tách content thành các dòng thoại
        List<String> rawLines = splitDialogueTurns(content, rolePattern);

        // 2. Bóc tách phoneticContent
        String rawPhonetic = (phoneticContent != null) ? phoneticContent.trim() : "";
        List<String> rawPhoneticLines = splitDialogueTurns(rawPhonetic, rolePattern);

        java.util.Map<String, List<String>> roleToPhonetics = new java.util.HashMap<>();
        List<String> cleanedPhoneticList = new ArrayList<>();

        for (String pLine : rawPhoneticLines) {
            String pTrim = pLine.trim();
            if (pTrim.isEmpty()) continue;

            Matcher pMatcher = rolePattern.matcher(pTrim);
            if (pMatcher.find()) {
                String pRole = pMatcher.group(1).trim().toUpperCase();
                String pText = pMatcher.group(2).trim();
                roleToPhonetics.computeIfAbsent(pRole, k -> new ArrayList<>()).add(pText);
                cleanedPhoneticList.add(pText);
            } else {
                cleanedPhoneticList.add(pTrim);
            }
        }

        // 3. Bóc tách meaningContent (Nghĩa tiếng Việt của script)
        String rawMeaning = (meaningContent != null) ? meaningContent.trim() : "";
        List<String> rawMeaningLines = splitDialogueTurns(rawMeaning, rolePattern);

        java.util.Map<String, List<String>> roleToMeanings = new java.util.HashMap<>();
        List<String> cleanedMeaningList = new ArrayList<>();

        for (String mLine : rawMeaningLines) {
            String mTrim = mLine.trim();
            if (mTrim.isEmpty()) continue;

            Matcher mMatcher = rolePattern.matcher(mTrim);
            if (mMatcher.find()) {
                String mRole = mMatcher.group(1).trim().toUpperCase();
                String mText = mMatcher.group(2).trim();
                roleToMeanings.computeIfAbsent(mRole, k -> new ArrayList<>()).add(mText);
                cleanedMeaningList.add(mText);
            } else {
                cleanedMeaningList.add(mTrim);
            }
        }

        // Đếm số lần xuất hiện của từng vai để map 1-1 với phonetic & meaning
        java.util.Map<String, Integer> roleCounter = new java.util.HashMap<>();
        String currentRole = "A";
        int validLineCount = 0;

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher matcher = rolePattern.matcher(line);
            if (matcher.find()) {
                currentRole = matcher.group(1).trim();
                line = matcher.group(2).trim();
            } else if (rawLines.size() > 1) {
                currentRole = (validLineCount % 2 == 0) ? "A" : "B";
            }

            if (!line.isEmpty()) {
                String roleKey = currentRole.toUpperCase();
                int turnIdx = roleCounter.getOrDefault(roleKey, 0);
                roleCounter.put(roleKey, turnIdx + 1);

                String phonetic = "";
                // Ưu tiên 1: Lấy phonetic theo đúng vai và đúng lượt xuất hiện của vai đó
                if (roleToPhonetics.containsKey(roleKey) && turnIdx < roleToPhonetics.get(roleKey).size()) {
                    phonetic = roleToPhonetics.get(roleKey).get(turnIdx);
                }
                // Ưu tiên 2: Lấy phonetic theo thứ tự dòng tuần tự
                else if (validLineCount < cleanedPhoneticList.size()) {
                    phonetic = cleanedPhoneticList.get(validLineCount);
                }
                // Ưu tiên 3: Fallback lấy toàn bộ phoneticContent nếu chỉ có 1 khối
                else if (!rawPhonetic.isEmpty()) {
                    phonetic = rawPhonetic;
                }

                String meaning = "";
                // Ưu tiên 1: Lấy meaning theo đúng vai và đúng lượt xuất hiện của vai đó
                if (roleToMeanings.containsKey(roleKey) && turnIdx < roleToMeanings.get(roleKey).size()) {
                    meaning = roleToMeanings.get(roleKey).get(turnIdx);
                }
                // Ưu tiên 2: Lấy meaning theo thứ tự dòng tuần tự
                else if (validLineCount < cleanedMeaningList.size()) {
                    meaning = cleanedMeaningList.get(validLineCount);
                }
                // Ưu tiên 3: Fallback lấy toàn bộ meaningContent
                else if (!rawMeaning.isEmpty()) {
                    meaning = rawMeaning;
                }

                result.add(SentenceRoleDTO.builder()
                        .role(currentRole)
                        .text(line)
                        .phonetic(phonetic)
                        .meaning(meaning)
                        .build());
                validLineCount++;
            }
        }

        return result;
    }

    private static List<String> splitDialogueTurns(String text, Pattern rolePattern) {
        List<String> list = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return list;
        }

        // Nếu có dấu xuống dòng: tách theo xuống dòng
        if (text.contains("\n")) {
            for (String s : text.split("\\r?\\n")) {
                if (!s.trim().isEmpty()) {
                    list.add(s.trim());
                }
            }
            return list;
        }

        // Nếu viết trên 1 dòng duy nhất, thử tách theo vị trí xuất hiện của vai
        String[] byRoles = text.split("(?<=\\s|^)(?=[\\p{L}0-9_\\s]+[:：])");
        if (byRoles.length > 1) {
            for (String s : byRoles) {
                if (!s.trim().isEmpty()) {
                    list.add(s.trim());
                }
            }
            return list;
        }

        // Nếu không có mốc vai, tách theo dấu chấm câu
        String[] bySentences = text.split("(?<=[。！？!?.])\\s*");
        for (String s : bySentences) {
            if (!s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
        return list;
    }
}
