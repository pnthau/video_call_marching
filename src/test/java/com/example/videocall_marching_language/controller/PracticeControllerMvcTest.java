package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.dto.script.ScriptResponse;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.service.script.PracticeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PracticeControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PracticeService practiceService;

    @Test
    void testScriptsPageRendersWithCriteriaModelAttributes() throws Exception {
        Tag topic = Tag.builder().id(5L).name("Du lịch & Hỏi đường").build();
        ScriptResponse s1 = ScriptResponse.builder()
                .id(1L)
                .title("Hỏi đường đến ga Tokyo")
                .tagId(5L)
                .tagName("Du lịch & Hỏi đường")
                .language("ja")
                .targetDuration(50)
                .build();

        when(practiceService.findScriptsByCriteria(any())).thenReturn(List.of(s1));
        when(practiceService.getAvailableTopics()).thenReturn(List.of(topic));
        when(practiceService.getAvailableLanguages()).thenReturn(List.of("ja", "en"));
        when(practiceService.getTopicsWithScriptCount()).thenReturn(List.of());
        when(practiceService.getTopicsWithCountAsJson()).thenReturn("[]");

        mockMvc.perform(get("/practice/scripts")
                        .param("tagId", "5")
                        .param("language", "ja")
                        .param("level", "n5")
                        .param("phase", "2")
                        .with(user("test@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("scripts"))
                .andExpect(model().attributeExists("criteria"))
                .andExpect(model().attributeExists("topicsWithCount"))
                .andExpect(model().attributeExists("topicsWithCountJson"))
                .andExpect(model().attribute("phase", 2))
                .andExpect(model().attribute("selectedTopicName", "Du lịch & Hỏi đường"))
                .andExpect(model().attribute("selectedLevel", "N5"))
                .andExpect(model().attribute("shouldOpenModal", false))
                .andExpect(view().name("users/scripts/list"));
    }

    @Test
    void testScriptsPageWithoutCriteria_ShouldOpenModal() throws Exception {
        when(practiceService.findScriptsByCriteria(any())).thenReturn(List.of());
        when(practiceService.getAvailableTopics()).thenReturn(List.of());
        when(practiceService.getAvailableLanguages()).thenReturn(List.of("ja", "en"));

        mockMvc.perform(get("/practice/scripts")
                        .with(user("test@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("shouldOpenModal", true))
                .andExpect(view().name("users/scripts/list"));
    }
}
