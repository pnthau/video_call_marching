package com.example.videocall_marching_language.service;

import io.agora.media.RtcTokenBuilder2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgoraTokenService {

    @Value("${agora.app-id}")
    private String appId;

    @Value("${agora.app-certificate}")
    private String appCertificate;

    private static final int TOKEN_EXPIRATION_IN_SECONDS = 3600;

    public String generateToken(String channelName, int uid) {
        RtcTokenBuilder2 tokenBuilder = new RtcTokenBuilder2();

        return tokenBuilder.buildTokenWithUid(
                appId,
                appCertificate,
                channelName,
                uid,
                RtcTokenBuilder2.Role.ROLE_PUBLISHER,
                TOKEN_EXPIRATION_IN_SECONDS,
                TOKEN_EXPIRATION_IN_SECONDS
        );
    }
}
