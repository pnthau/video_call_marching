package com.example.videocall_marching_language.dto;

import lombok.Data;
import java.util.Map;

@Data
public class PeerRatingRequest {
    private Long sessionId;
    private Long rateeId;
    private Map<String, Integer> scores;
    private String comment;
}
