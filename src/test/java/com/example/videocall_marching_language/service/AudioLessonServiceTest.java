package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.AudioLesson;
import com.example.videocall_marching_language.entity.LessonSentence;
import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.AudioLessonStatus;
import com.example.videocall_marching_language.repository.*;
import com.example.videocall_marching_language.service.audiolesson.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AudioLessonServiceTest {

    @Mock private AudioStorageService audioStorageService;
    @Mock private WhisperTranscriptionService whisperService;
    @Mock private AiLessonGeneratorService aiLessonGeneratorService;
    @Mock private GrammarEvaluationService grammarEvaluationService;

    @Mock private IAudioLessonRepository audioLessonRepository;
    @Mock private ILessonSentenceRepository lessonSentenceRepository;
    @Mock private ISentenceExerciseRepository sentenceExerciseRepository;
    @Mock private IScriptRepository scriptRepository;
    @Mock private ITagRepository tagRepository;
    @Mock private IUserRepository userRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private AudioLessonService audioLessonService;

    @BeforeEach
    void setUp() {
        audioLessonService = new AudioLessonService(
                audioStorageService,
                whisperService,
                aiLessonGeneratorService,
                grammarEvaluationService,
                audioLessonRepository,
                lessonSentenceRepository,
                sentenceExerciseRepository,
                scriptRepository,
                tagRepository,
                userRepository,
                transactionManager
        );
    }

    @Test
    void getOrCreateRoleplayScript_WhenScriptAlreadyExists_ReturnsExistingId() {
        Script existingScript = Script.builder()
                .id(99L)
                .title("Kịch bản cũ")
                .build();

        AudioLesson lesson = AudioLesson.builder()
                .id(1L)
                .title("Bài học tiếng Nhật")
                .script(existingScript)
                .build();

        when(audioLessonRepository.findById(1L)).thenReturn(Optional.of(lesson));

        Long scriptId = audioLessonService.getOrCreateRoleplayScript(1L);

        assertEquals(99L, scriptId);
        verify(scriptRepository, never()).save(any());
        verify(lessonSentenceRepository, never()).findByAudioLessonIdOrderBySentenceIndexAsc(any());
    }

    @Test
    void getOrCreateRoleplayScript_WhenScriptDoesNotExist_GeneratesAndReturnsNewId() {
        Tag tag = Tag.builder().id(5L).name("Chào hỏi").build();
        AudioLesson lesson = AudioLesson.builder()
                .id(1L)
                .title("Bài học chào hỏi")
                .language("ja")
                .durationInSeconds(45)
                .tag(tag)
                .status(AudioLessonStatus.READY)
                .build();

        LessonSentence s1 = LessonSentence.builder()
                .id(101L)
                .sentenceIndex(0)
                .originalText("はじめまして。")
                .phonetic("Hajimemashite.")
                .vietnameseMeaning("Rất vui được gặp bạn.")
                .build();

        LessonSentence s2 = LessonSentence.builder()
                .id(102L)
                .sentenceIndex(1)
                .originalText("こちらこそ、よろしく。")
                .phonetic("Kochirakoso, yoroshiku.")
                .vietnameseMeaning("Chính tôi mới cần bạn giúp đỡ.")
                .build();

        when(audioLessonRepository.findById(1L)).thenReturn(Optional.of(lesson));
        when(lessonSentenceRepository.findByAudioLessonIdOrderBySentenceIndexAsc(1L)).thenReturn(List.of(s1, s2));

        when(scriptRepository.save(any(Script.class))).thenAnswer(invocation -> {
            Script s = invocation.getArgument(0);
            s.setId(200L);
            return s;
        });

        Long scriptId = audioLessonService.getOrCreateRoleplayScript(1L);

        assertEquals(200L, scriptId);

        ArgumentCaptor<Script> scriptCaptor = ArgumentCaptor.forClass(Script.class);
        verify(scriptRepository).save(scriptCaptor.capture());
        Script savedScript = scriptCaptor.getValue();

        assertEquals("[Roleplay] Bài học chào hỏi", savedScript.getTitle());
        assertEquals("ja", savedScript.getLanguage());
        assertEquals(45, savedScript.getTargetDuration());
        assertEquals(tag, savedScript.getTag());

        // Kiểm tra xen kẽ A và B
        assertTrue(savedScript.getContent().contains("A: はじめまして。"));
        assertTrue(savedScript.getContent().contains("B: こちらこそ、よろしく。"));

        assertTrue(savedScript.getPhoneticContent().contains("A: Hajimemashite."));
        assertTrue(savedScript.getPhoneticContent().contains("B: Kochirakoso, yoroshiku."));

        assertTrue(savedScript.getMeaningContent().contains("A: Rất vui được gặp bạn."));
        assertTrue(savedScript.getMeaningContent().contains("B: Chính tôi mới cần bạn giúp đỡ."));

        // Kiểm tra đã gán script cho lesson và lưu lại
        assertEquals(savedScript, lesson.getScript());
        verify(audioLessonRepository).save(lesson);
    }

    @Test
    void getOrCreateRoleplayScript_WhenLessonHasNoSentences_ThrowsIllegalStateException() {
        AudioLesson lesson = AudioLesson.builder()
                .id(1L)
                .title("Bài học rỗng")
                .build();

        when(audioLessonRepository.findById(1L)).thenReturn(Optional.of(lesson));
        when(lessonSentenceRepository.findByAudioLessonIdOrderBySentenceIndexAsc(1L)).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> audioLessonService.getOrCreateRoleplayScript(1L));
    }

    @Test
    void findLessonsByCriteria_WhenRequestIsNull_ReturnsAllLessons() {
        Tag tag = Tag.builder().id(1L).name("Chủ đề 1").build();
        AudioLesson lesson1 = AudioLesson.builder().id(10L).title("Bài 1").tag(tag).language("ja").level("N5").status(AudioLessonStatus.READY).build();
        AudioLesson lesson2 = AudioLesson.builder().id(20L).title("Bài 2").tag(tag).language("en").level("A1").status(AudioLessonStatus.READY).build();

        when(audioLessonRepository.findAllWithTag()).thenReturn(List.of(lesson1, lesson2));

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(null);

        assertEquals(2, results.size());
        assertEquals(10L, results.get(0).getId());
        assertEquals(20L, results.get(1).getId());
        verify(audioLessonRepository).findAllWithTag();
        verify(audioLessonRepository, never()).findByCriteria(any(), any(), any());
    }

    @Test
    void findLessonsByCriteria_WhenRequestIsEmpty_PassesNullsToRepository() {
        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                new com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest();
        Tag tag = Tag.builder().id(1L).name("Chủ đề").build();
        AudioLesson lesson = AudioLesson.builder().id(10L).title("Bài 1").tag(tag).language("ja").level("N5").status(AudioLessonStatus.READY).build();

        when(audioLessonRepository.findByCriteria(null, null, null)).thenReturn(List.of(lesson));

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        verify(audioLessonRepository).findByCriteria(null, null, null);
    }

    @Test
    void findLessonsByCriteria_WhenFilteringByLanguageOnly_PassesLanguageLowerCase() {
        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest.builder().language("JA").build();
        Tag tag = Tag.builder().id(1L).name("Chủ đề").build();
        AudioLesson lesson = AudioLesson.builder().id(10L).title("Bài 1").tag(tag).language("ja").level("N5").status(AudioLessonStatus.READY).build();

        when(audioLessonRepository.findByCriteria(null, "ja", null)).thenReturn(List.of(lesson));

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        assertEquals("ja", results.get(0).getLanguage());
        verify(audioLessonRepository).findByCriteria(null, "ja", null);
    }

    @Test
    void findLessonsByCriteria_WhenFilteringByLevelOnly_PassesLevelLowerCase() {
        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest.builder().level("N3").build();
        Tag tag = Tag.builder().id(1L).name("Chủ đề").build();
        AudioLesson lesson = AudioLesson.builder().id(10L).title("Bài 1").tag(tag).language("ja").level("N3").status(AudioLessonStatus.READY).build();

        when(audioLessonRepository.findByCriteria(null, null, "n3")).thenReturn(List.of(lesson));

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        assertEquals("N3", results.get(0).getLevel());
        verify(audioLessonRepository).findByCriteria(null, null, "n3");
    }

    @Test
    void findLessonsByCriteria_WhenFilteringByTagIdOnly_PassesTagId() {
        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest.builder().tagId(7L).build();
        Tag tag = Tag.builder().id(7L).name("Ẩm thực").build();
        AudioLesson lesson = AudioLesson.builder().id(10L).title("Tại nhà hàng").tag(tag).language("ja").level("N5").status(AudioLessonStatus.READY).build();

        when(audioLessonRepository.findByCriteria(7L, null, null)).thenReturn(List.of(lesson));

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        assertEquals(7L, results.get(0).getTagId());
        assertEquals("Ẩm thực", results.get(0).getTagName());
        verify(audioLessonRepository).findByCriteria(7L, null, null);
    }

    @Test
    void findLessonsByCriteria_WhenLanguageAndLevelAreAll_PassesNulls() {
        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest.builder().language("all").level("ALL").build();
        Tag tag = Tag.builder().id(1L).name("Chủ đề").build();
        AudioLesson lesson = AudioLesson.builder().id(10L).title("Bài 1").tag(tag).language("ja").level("N5").status(AudioLessonStatus.READY).build();

        when(audioLessonRepository.findByCriteria(null, null, null)).thenReturn(List.of(lesson));

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        verify(audioLessonRepository).findByCriteria(null, null, null);
    }

    @Test
    void findLessonsByCriteria_MapsAllDtoFieldsCorrectly() {
        Tag tag = Tag.builder().id(3L).name("Công sở").build();
        User user = User.builder().id(99L).username("testuser").build();
        Script script = Script.builder().id(88L).title("Kịch bản").build();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        AudioLesson lesson = AudioLesson.builder()
                .id(10L)
                .title("Họp dự án")
                .tag(tag)
                .user(user)
                .audioUrl("https://example.com/audio.mp3")
                .durationInSeconds(120)
                .language("ja")
                .level("N2")
                .status(AudioLessonStatus.READY)
                .script(script)
                .createdAt(now)
                .build();

        when(audioLessonRepository.findByCriteria(3L, "ja", "n2")).thenReturn(List.of(lesson));

        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest.builder()
                        .tagId(3L)
                        .language("ja")
                        .level("N2")
                        .build();

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO dto = results.get(0);
        assertEquals(10L, dto.getId());
        assertEquals(3L, dto.getTagId());
        assertEquals("Công sở", dto.getTagName());
        assertEquals(99L, dto.getUserId());
        assertEquals("Họp dự án", dto.getTitle());
        assertEquals("https://example.com/audio.mp3", dto.getAudioUrl());
        assertEquals(120, dto.getDurationInSeconds());
        assertEquals("ja", dto.getLanguage());
        assertEquals("N2", dto.getLevel());
        assertEquals(AudioLessonStatus.READY.name(), dto.getStatus());
        assertEquals(88L, dto.getScriptId());
        assertEquals(now.toString(), dto.getCreatedAt());
    }

    @Test
    void findLessonsByCriteria_FiltersCorrectly() {
        Tag tag = Tag.builder().id(2L).name("Du lịch").build();
        AudioLesson lesson = AudioLesson.builder()
                .id(10L)
                .title("Tại sân bay")
                .tag(tag)
                .language("ja")
                .level("N4")
                .build();

        when(audioLessonRepository.findByCriteria(2L, "ja", "n4"))
                .thenReturn(List.of(lesson));

        com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest request =
                com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest.builder()
                        .tagId(2L)
                        .language("ja")
                        .level("N4")
                        .build();

        List<com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO> results =
                audioLessonService.findLessonsByCriteria(request);

        assertEquals(1, results.size());
        assertEquals("Tại sân bay", results.get(0).getTitle());
        assertEquals("ja", results.get(0).getLanguage());
        assertEquals("N4", results.get(0).getLevel());
    }

    @Test
    void getTopicsWithAudioLessonCount_ReturnsList() {
        com.example.videocall_marching_language.dto.script.TopicWithCountDTO dto =
                new com.example.videocall_marching_language.dto.script.TopicWithCountDTO(1L, "Chào hỏi", "ja", "n5", 3L);
        when(audioLessonRepository.findTopicsWithAudioLessonCount()).thenReturn(List.of(dto));

        List<com.example.videocall_marching_language.dto.script.TopicWithCountDTO> list =
                audioLessonService.getTopicsWithAudioLessonCount();

        assertEquals(1, list.size());
        assertEquals("Chào hỏi", list.get(0).getTagName());
        assertEquals(3L, list.get(0).getScriptCount());
    }

    @Test
    void getTopicsWithCountAsJson_WhenTopicsExist_ReturnsValidJson() {
        com.example.videocall_marching_language.dto.script.TopicWithCountDTO dto1 =
                new com.example.videocall_marching_language.dto.script.TopicWithCountDTO(1L, "Chào hỏi", "ja", "n5", 5L);
        com.example.videocall_marching_language.dto.script.TopicWithCountDTO dto2 =
                new com.example.videocall_marching_language.dto.script.TopicWithCountDTO(2L, "Mua sắm", "en", "a1", 3L);
        when(audioLessonRepository.findTopicsWithAudioLessonCount()).thenReturn(List.of(dto1, dto2));

        String json = audioLessonService.getTopicsWithCountAsJson();

        assertNotNull(json);
        assertTrue(json.contains("\"tagId\":1"));
        assertTrue(json.contains("\"tagName\":\"Chào hỏi\""));
        assertTrue(json.contains("\"language\":\"ja\""));
        assertTrue(json.contains("\"level\":\"n5\""));
        assertTrue(json.contains("\"scriptCount\":5"));
        assertTrue(json.contains("\"tagId\":2"));
        assertTrue(json.contains("\"tagName\":\"Mua sắm\""));
    }

    @Test
    void getTopicsWithCountAsJson_WhenEmpty_ReturnsEmptyJsonArray() {
        when(audioLessonRepository.findTopicsWithAudioLessonCount()).thenReturn(List.of());

        String json = audioLessonService.getTopicsWithCountAsJson();

        assertEquals("[]", json);
    }

    @Test
    void getAvailableLanguages_WhenRepositoryReturnsLanguages_ReturnsThem() {
        when(audioLessonRepository.findDistinctLanguages()).thenReturn(List.of("ja", "en", "vi"));

        List<String> langs = audioLessonService.getAvailableLanguages();

        assertEquals(3, langs.size());
        assertEquals(List.of("ja", "en", "vi"), langs);
    }

    @Test
    void getAvailableLanguages_WhenEmpty_ReturnsDefault() {
        when(audioLessonRepository.findDistinctLanguages()).thenReturn(List.of());

        List<String> langs = audioLessonService.getAvailableLanguages();

        assertEquals(2, langs.size());
        assertTrue(langs.contains("ja"));
        assertTrue(langs.contains("en"));
    }

    @Test
    void getAvailableLanguages_WhenNull_ReturnsDefault() {
        when(audioLessonRepository.findDistinctLanguages()).thenReturn(null);

        List<String> langs = audioLessonService.getAvailableLanguages();

        assertEquals(2, langs.size());
        assertTrue(langs.contains("ja"));
        assertTrue(langs.contains("en"));
    }
}
