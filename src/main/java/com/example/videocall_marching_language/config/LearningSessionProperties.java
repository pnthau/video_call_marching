package com.example.videocall_marching_language.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "learning-session")
public class LearningSessionProperties {

    private int minimumOverlapSeconds = 300;
    private int reconnectGraceSeconds = 60;
    private int maximumDurationSeconds = 3600;

    public int getMinimumOverlapSeconds() {
        return minimumOverlapSeconds;
    }

    public void setMinimumOverlapSeconds(int minimumOverlapSeconds) {
        this.minimumOverlapSeconds = minimumOverlapSeconds;
    }

    public int getReconnectGraceSeconds() {
        return reconnectGraceSeconds;
    }

    public void setReconnectGraceSeconds(int reconnectGraceSeconds) {
        this.reconnectGraceSeconds = reconnectGraceSeconds;
    }

    public int getMaximumDurationSeconds() {
        return maximumDurationSeconds;
    }

    public void setMaximumDurationSeconds(int maximumDurationSeconds) {
        this.maximumDurationSeconds = maximumDurationSeconds;
    }
}