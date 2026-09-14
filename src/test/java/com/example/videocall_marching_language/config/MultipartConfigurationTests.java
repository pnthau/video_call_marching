package com.example.videocall_marching_language.config;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class MultipartConfigurationTests {

    private static final long FIVE_MIB = 5L * 1024 * 1024;
    private static final long SIX_MIB = 6L * 1024 * 1024;

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private MultipartConfigElement multipartConfigElement;

    @Test
    void defaultProfileBindsFiveMiBFileAndSixMiBRequestLimits() {
        assertEquals(FIVE_MIB, multipartProperties.getMaxFileSize().toBytes());
        assertEquals(SIX_MIB, multipartProperties.getMaxRequestSize().toBytes());
        assertEquals(FIVE_MIB, multipartConfigElement.getMaxFileSize());
        assertEquals(SIX_MIB, multipartConfigElement.getMaxRequestSize());
    }
}
