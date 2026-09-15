package com.example.videocall_marching_language.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TtsAudioResponse {
    private String status;
    private String audioBase64;
    private String message;
}
