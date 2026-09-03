package com.example.videocall_marching_language.dto.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTokenResponse {
    private String token;
    private String channelName;
    private int uid;
}