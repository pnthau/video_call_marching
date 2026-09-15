package com.example.videocall_marching_language.dto.tts;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TtsSegmentResponse {
    private String text;
    private String lang; // "vi-VN", "en-US", "ja-JP", etc.
    private String audioBase64;
}
