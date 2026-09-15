package com.example.videocall_marching_language.dto.tts;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TtsAudioRequest {
    private String text;
    private String language;
}
