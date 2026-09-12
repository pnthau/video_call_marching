package com.example.videocall_marching_language.service.ai;

import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.entity.UserAiSetting;
import com.example.videocall_marching_language.enums.AIProvider;
import com.example.videocall_marching_language.repository.IUserAiSettingRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiProxyService {
    private final Map<AIProvider, IAIService> aiServiceMap;
    private final IUserAiSettingRepository userAiSettingRepository;
    private final IUserRepository userRepository;

    public AiProxyService(List<IAIService> aiServices,
                          IUserAiSettingRepository userAiSettingRepository,
                          IUserRepository userRepository) {
        this.userAiSettingRepository = userAiSettingRepository;
        this.userRepository = userRepository;
        // Biến List thành Map để tra cứu nhanh: ví dụ Map chứa {GEMINI -> GeminiAiServiceImpl, GROK -> GrokAiServiceImpl}
        this.aiServiceMap = aiServices.stream()
                .collect(Collectors.toMap(IAIService::getProvider, Function.identity()));
    }

    @org.springframework.beans.factory.annotation.Value("${gemini.api.key:}")
    private String globalGeminiKey;

    public String chat(Long userId, String prompt) {
        AIProvider provider = AIProvider.GEMINI;
        String apiKey = globalGeminiKey;

        if (userId != null) {
            User user = userRepository.findById(userId).orElse(null);
            AIProvider targetProvider = (user != null && user.getActiveAiProvider() != null)
                    ? user.getActiveAiProvider()
                    : null;

            UserAiSetting setting = null;
            if (targetProvider != null) {
                setting = userAiSettingRepository.findByUserIdAndAiProvider(userId, targetProvider).orElse(null);
                if (setting == null && targetProvider == AIProvider.GROG) {
                    setting = userAiSettingRepository.findByUserIdAndAiProvider(userId, AIProvider.GROQ).orElse(null);
                } else if (setting == null && targetProvider == AIProvider.GROQ) {
                    setting = userAiSettingRepository.findByUserIdAndAiProvider(userId, AIProvider.GROG).orElse(null);
                }
            }

            // Nếu user chưa cấu hình activeProvider hoặc provider đó chưa có key, lấy key khả dụng đầu tiên
            if (setting == null || setting.getAiApiKey() == null || setting.getAiApiKey().isBlank()) {
                List<UserAiSetting> settings = userAiSettingRepository.findByUserId(userId);
                for (UserAiSetting s : settings) {
                    if (s.getAiApiKey() != null && !s.getAiApiKey().isBlank()) {
                        setting = s;
                        break;
                    }
                }
            }

            if (setting != null && setting.getAiApiKey() != null && !setting.getAiApiKey().isBlank()) {
                provider = setting.getAiProvider();
                apiKey = setting.getAiApiKey();
            }
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("API Key chưa được cấu hình. Vui lòng cập nhật trong Profile hoặc application.properties");
        }

        // 2. Tìm đúng Service để xử lý
        IAIService aiService = aiServiceMap.get(provider);
        if (aiService == null && provider == AIProvider.GROG) {
            aiService = aiServiceMap.get(AIProvider.GROQ);
        }
        if (aiService == null && provider == AIProvider.GROQ) {
            aiService = aiServiceMap.get(AIProvider.GROG);
        }
        if (aiService == null) {
            throw new RuntimeException("Hệ thống chưa hỗ trợ AI Provider này: " + provider);
        }

        return aiService.generateResponse(prompt, apiKey);
    }
}
