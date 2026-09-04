package com.example.videocall_marching_language.dto;

import com.example.videocall_marching_language.enums.JapaneseLevel;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MatchResultDTO {
    private String status;
    private String channelName;
    private Long peerId;
    private String peerUserName;
    private Long sessionId;
    private JapaneseLevel levelSnapshot;
    private String tagSnapshot;
}
