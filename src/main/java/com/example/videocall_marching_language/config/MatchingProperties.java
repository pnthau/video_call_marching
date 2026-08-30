package com.example.videocall_marching_language.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    private int adjacentLevelAfterSeconds = 120;
    private int matchTimeoutSeconds = 600;

    public int getAdjacentLevelAfterSeconds() {
        return adjacentLevelAfterSeconds;
    }

    public void setAdjacentLevelAfterSeconds(int adjacentLevelAfterSeconds) {
        this.adjacentLevelAfterSeconds = adjacentLevelAfterSeconds;
    }

    public int getMatchTimeoutSeconds() {
        return matchTimeoutSeconds;
    }

    public void setMatchTimeoutSeconds(int matchTimeoutSeconds) {
        this.matchTimeoutSeconds = matchTimeoutSeconds;
    }
}