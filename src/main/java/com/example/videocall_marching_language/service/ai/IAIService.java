package com.example.videocall_marching_language.service.ai;

import com.example.videocall_marching_language.enums.AIProvider;

public interface IAIService {
    AIProvider getProvider();
    String generateResponse(String prompt, String apiKey);
}
