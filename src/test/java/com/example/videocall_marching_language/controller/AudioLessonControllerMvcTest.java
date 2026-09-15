package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.dto.TagOptionDTO;
import com.example.videocall_marching_language.dto.audiolesson.*;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.ExerciseType;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.service.audiolesson.AudioLessonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AudioLessonControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AudioLessonService audioLessonService;

    @MockitoBean
    private ITagRepository tagRepository;

    @MockitoBean
    private IUserService userService;

    @Test
    void testLearnPageRendersSuccessfully() throws Exception {
        AudioLessonDTO lesson = AudioLessonDTO.builder()
                .id(1L)
                .title("Bài học tiếng Nhật cơ bản")
                .tagName("Giới thiệu bản thân")
                .tagId(10L)
                .audioUrl("https://res.cloudinary.com/demo/audio.mp3")
                .durationInSeconds(45)
                .language("ja")
                .sentences(List.of(
                        LessonSentenceDTO.builder()
                                .id(1L)
                                .sentenceIndex(0)
                                .originalText("はじめまして、田中です。")
                                .vietnameseMeaning("Rất vui được gặp bạn, tôi là Tanaka.")
                                .phonetic("Hajimemashite, Tanaka desu.")
                                .startTime(0.0)
                                .endTime(3.5)
                                .grammar(SentenceGrammarDTO.builder()
                                        .grammarPoint("〜です")
                                        .formula("Danh từ + です")
                                        .explanation("Khẳng định lịch sự")
                                        .keyWordsJson("[{\"word\":\"田中\",\"meaning\":\"Tanaka\"}]")
                                        .sentenceChallengePrompt("Hãy tự giới thiệu tên mình")
                                        .build())
                                .exercises(List.of(
                                        SentenceExerciseDTO.builder()
                                                .exerciseType(ExerciseType.LISTENING_FILL_BLANK.name())
                                                .question("Chọn từ thích hợp điền vào chỗ trống: はじめまして、田中___。")
                                                .options(List.of("です", "ます", "でした", "でしたら"))
                                                .correctAnswer("です")
                                                .explanation("Dùng です sau danh từ")
                                                .build()
                                ))
                                .build()
                ))
                .build();

        when(userService.findByEmail("testuser@example.com"))
                .thenReturn(Optional.of(User.builder().id(1L).email("testuser@example.com").build()));
        when(audioLessonService.getLessonById(1L)).thenReturn(lesson);

        mockMvc.perform(get("/practice/audio-lessons/1")
                        .with(user("testuser@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("users/audio_lessons/learn"))
                .andExpect(model().attributeExists("lesson"))
                .andExpect(model().attributeExists("lessonJson"))
                .andExpect(model().attribute("currentUserId", 1L));
    }

    @Test
    void testRoleplayRedirectSuccessfully() throws Exception {
        when(audioLessonService.getOrCreateRoleplayScript(1L)).thenReturn(42L);

        mockMvc.perform(get("/practice/audio-lessons/1/roleplay")
                        .with(user("testuser@example.com").roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/practice/scripts/42"));
    }

    @Test
    void testListAudioLessonsWithCriteria() throws Exception {
        AudioLessonDTO lesson = AudioLessonDTO.builder()
                .id(1L)
                .title("Chào hỏi tiếng Nhật")
                .tagId(10L)
                .tagName("Chào hỏi")
                .language("ja")
                .level("N5")
                .durationInSeconds(60)
                .build();

        TopicWithCountDTO topicWithCount = new TopicWithCountDTO(10L, "Chào hỏi", "ja", "n5", 3L);
        Tag tag = Tag.builder().id(10L).name("Chào hỏi").build();
        TagCategory topicCat = TagCategory.builder().id(1L).name("Topic").type(TagCategoryType.TOPIC).build();
        Tag activeTag = Tag.builder().id(10L).name("Chào hỏi").tagCategory(topicCat).build();
        String jsonCounts = "[{\"tagId\":10,\"tagName\":\"Chào hỏi\",\"language\":\"ja\",\"level\":\"n5\",\"scriptCount\":3}]";

        when(userService.findByEmail("testuser@example.com"))
                .thenReturn(Optional.of(User.builder().id(2L).email("testuser@example.com").build()));
        when(audioLessonService.findLessonsByCriteria(any(AudioLessonRequest.class)))
                .thenReturn(List.of(lesson));
        when(audioLessonService.getTopicsWithAudioLessonCount())
                .thenReturn(List.of(topicWithCount));
        when(audioLessonService.getTopicsWithCountAsJson())
                .thenReturn(jsonCounts);
        when(audioLessonService.getAvailableLanguages())
                .thenReturn(List.of("ja", "en"));
        when(tagRepository.findByTagCategoryType(TagCategoryType.TOPIC))
                .thenReturn(List.of(tag));
        when(tagRepository.findAllForActiveCategories())
                .thenReturn(List.of(activeTag));

        mockMvc.perform(get("/practice/audio-lessons")
                        .param("tagId", "10")
                        .param("language", "ja")
                        .param("level", "n5")
                        .param("focus", "dictation")
                        .with(user("testuser@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("users/audio_lessons/list"))
                .andExpect(model().attributeExists("lessons"))
                .andExpect(model().attributeExists("criteria"))
                .andExpect(model().attribute("topicsWithCountJson", jsonCounts))
                .andExpect(model().attribute("shouldOpenModal", false))
                .andExpect(model().attribute("selectedTopicName", "Chào hỏi"))
                .andExpect(model().attribute("selectedLevel", "N5"))
                .andExpect(model().attributeExists("availableTopics"))
                .andExpect(model().attributeExists("availableLanguages"))
                .andExpect(model().attributeExists("topicsWithCount"))
                .andExpect(model().attributeExists("topicTags"))
                .andExpect(model().attribute("currentUserId", 2L))
                .andExpect(model().attribute("currentUserEmail", "testuser@example.com"));
    }

    @Test
    void testListAudioLessonsWithoutCriteria_ShouldOpenModal() throws Exception {
        when(userService.findByEmail("testuser@example.com"))
                .thenReturn(Optional.of(User.builder().id(2L).email("testuser@example.com").build()));
        when(audioLessonService.findLessonsByCriteria(any(AudioLessonRequest.class)))
                .thenReturn(List.of());
        when(audioLessonService.getTopicsWithAudioLessonCount())
                .thenReturn(List.of());
        when(audioLessonService.getTopicsWithCountAsJson())
                .thenReturn("[]");
        when(audioLessonService.getAvailableLanguages())
                .thenReturn(List.of("ja", "en"));
        when(tagRepository.findByTagCategoryType(TagCategoryType.TOPIC))
                .thenReturn(List.of());
        when(tagRepository.findAllForActiveCategories())
                .thenReturn(List.of());

        mockMvc.perform(get("/practice/audio-lessons")
                        .with(user("testuser@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("users/audio_lessons/list"))
                .andExpect(model().attributeExists("lessons"))
                .andExpect(model().attributeExists("criteria"))
                .andExpect(model().attribute("topicsWithCountJson", "[]"))
                .andExpect(model().attribute("shouldOpenModal", true))
                .andExpect(model().attribute("selectedTopicName", nullValue()))
                .andExpect(model().attribute("selectedLevel", nullValue()));
    }

    @Test
    void testListAudioLessonsWithAutoOpenParam_ShouldOpenModal() throws Exception {
        when(userService.findByEmail("testuser@example.com"))
                .thenReturn(Optional.of(User.builder().id(2L).email("testuser@example.com").build()));
        when(audioLessonService.findLessonsByCriteria(any(AudioLessonRequest.class)))
                .thenReturn(List.of());
        when(audioLessonService.getTopicsWithAudioLessonCount())
                .thenReturn(List.of());
        when(audioLessonService.getTopicsWithCountAsJson())
                .thenReturn("[]");
        when(audioLessonService.getAvailableLanguages())
                .thenReturn(List.of("ja", "en"));
        when(tagRepository.findByTagCategoryType(TagCategoryType.TOPIC))
                .thenReturn(List.of());
        when(tagRepository.findAllForActiveCategories())
                .thenReturn(List.of());

        mockMvc.perform(get("/practice/audio-lessons")
                        .param("tagId", "10")
                        .param("language", "ja")
                        .param("level", "n5")
                        .param("autoOpen", "true")
                        .with(user("testuser@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("users/audio_lessons/list"))
                .andExpect(model().attribute("shouldOpenModal", true))
                .andExpect(model().attribute("selectedLevel", "N5"));
    }

    @Test
    void testListAudioLessonsWithAllFilters_LanguageAndLevelAll_ShouldOpenModal() throws Exception {
        when(userService.findByEmail("testuser@example.com"))
                .thenReturn(Optional.of(User.builder().id(2L).email("testuser@example.com").build()));
        when(audioLessonService.findLessonsByCriteria(any(AudioLessonRequest.class)))
                .thenReturn(List.of());
        when(audioLessonService.getTopicsWithAudioLessonCount())
                .thenReturn(List.of());
        when(audioLessonService.getTopicsWithCountAsJson())
                .thenReturn("[]");
        when(audioLessonService.getAvailableLanguages())
                .thenReturn(List.of("ja", "en"));
        when(tagRepository.findByTagCategoryType(TagCategoryType.TOPIC))
                .thenReturn(List.of());
        when(tagRepository.findAllForActiveCategories())
                .thenReturn(List.of());

        mockMvc.perform(get("/practice/audio-lessons")
                        .param("language", "all")
                        .param("level", "all")
                        .param("focus", "all")
                        .with(user("testuser@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("users/audio_lessons/list"))
                .andExpect(model().attribute("shouldOpenModal", true))
                .andExpect(model().attribute("selectedLevel", nullValue()));
    }
}
