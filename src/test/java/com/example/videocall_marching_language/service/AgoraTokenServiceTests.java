package com.example.videocall_marching_language.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AgoraTokenServiceTests {

    private AgoraTokenService agoraTokenService;
    private static final String MOCK_APP_ID = "970ca35de60c44645bbae8a215061401";
    private static final String MOCK_APP_CERT = "5cfd2fd1755d40ecb72977518be15d3b";

    @BeforeEach
    void setUp() {
        agoraTokenService = new AgoraTokenService();
        ReflectionTestUtils.setField(agoraTokenService, "appId", MOCK_APP_ID);
        ReflectionTestUtils.setField(agoraTokenService, "appCertificate", MOCK_APP_CERT);
    }

    @Test
    void generateTokenReturnsValidTokenString() {
        String token = agoraTokenService.generateToken("room_test_100", 12345);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertTrue(token.length() > 20);
    }

    @Test
    void generateTokenProducesDifferentTokensForDifferentUids() {
        String token1 = agoraTokenService.generateToken("room_test_100", 101);
        String token2 = agoraTokenService.generateToken("room_test_100", 102);

        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2);
    }

    @Test
    void generateTokenProducesDifferentTokensForDifferentChannels() {
        String tokenA = agoraTokenService.generateToken("room_alpha", 101);
        String tokenB = agoraTokenService.generateToken("room_beta", 101);

        assertNotNull(tokenA);
        assertNotNull(tokenB);
        assertNotEquals(tokenA, tokenB);
    }
}
