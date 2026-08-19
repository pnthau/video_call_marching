package com.example.videocall_marching_language.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MatchResultDTO     {
    private String status;
    private String channelName;
    private Long peerId;
    private String peerUserName;
}
