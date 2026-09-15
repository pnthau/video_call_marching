package com.example.videocall_marching_language.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Objects;

/**
 * DTO đại diện cho một tùy chọn ngôn ngữ hiển thị trên giao diện (select dropdown, bộ lọc).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LanguageOption {

    /** Mã ngôn ngữ chuẩn ISO (ví dụ: "ja", "en", "ko", ...) */
    private String code;

    /** Nhãn đầy đủ hiển thị trên giao diện (ví dụ: "🇯🇵 Tiếng Nhật (Japanese)") */
    private String label;

    /** Biểu tượng lá cờ (ví dụ: "🇯🇵", "🇬🇧") */
    private String flag;

    /** Tên tiếng Việt ngắn gọn (ví dụ: "Tiếng Nhật", "Tiếng Anh") */
    private String name;

    public static LanguageOption fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String cleanCode = code.trim().toLowerCase();
        return switch (cleanCode) {
            case "ja" -> LanguageOption.builder()
                    .code("ja")
                    .label("Japanese")
                    .flag("🇯🇵")
                    .name("Tiếng Nhật")
                    .build();
            case "en" -> LanguageOption.builder()
                    .code("en")
                    .label("English")
                    .flag("🇬🇧")
                    .name("Tiếng Anh")
                    .build();
            case "ko" -> LanguageOption.builder()
                    .code("ko")
                    .label("Korean")
                    .flag("🇰🇷")
                    .name("Tiếng Hàn")
                    .build();
            case "zh" -> LanguageOption.builder()
                    .code("zh")
                    .label("Chinese")
                    .flag("🇨🇳")
                    .name("Tiếng Trung")
                    .build();
            default -> LanguageOption.builder()
                    .code(cleanCode)
                    .label(cleanCode.toUpperCase())
                    .flag("🌐")
                    .name(cleanCode.toUpperCase())
                    .build();
        };
    }

    public static List<LanguageOption> fromCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream()
                .map(LanguageOption::fromCode)
                .filter(Objects::nonNull)
                .toList();
    }
}
