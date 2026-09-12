package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.script.ScriptRequest;
import com.example.videocall_marching_language.dto.script.ScriptResponse;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IPracticeHistoryRepository;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.ai.AiEvaluationService;
import com.example.videocall_marching_language.service.script.PracticeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PracticeServiceCriteriaTest {

    @Mock private IScriptRepository scriptRepository;
    @Mock private IUserRepository userRepository;
    @Mock private IPracticeHistoryRepository practiceHistoryRepository;
    @Mock private ITagRepository tagRepository;
    @Mock private AiEvaluationService aiEvaluationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PracticeService practiceService;

    @BeforeEach
    void setUp() {
        practiceService = new PracticeService(
                scriptRepository,
                userRepository,
                practiceHistoryRepository,
                tagRepository,
                objectMapper,
                aiEvaluationService
        );
    }

    @Test
    void findScriptsByCriteria_WithTagIdAndLanguage_ReturnsFilteredScripts() {
        Tag topicTag = Tag.builder().id(10L).name("Mua sắm (Shopping)").build();
        Script s1 = Script.builder()
                .id(1L)
                .title("Mua sắm tại combini")
                .tag(topicTag)
                .language("ja")
                .targetDuration(60)
                .content("A: いらっしゃいませ\nB: ありがとう")
                .build();

        when(scriptRepository.findByCriteria(eq(10L), isNull(), eq(false), any(), eq("ja"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(s1));

        ScriptRequest request = ScriptRequest.builder()
                .tagId(10L)
                .language("ja")
                .phase(1)
                .build();

        List<ScriptResponse> responses = practiceService.findScriptsByCriteria(request);

        assertNotNull(responses);
        assertEquals(1, responses.size());
        ScriptResponse res = responses.get(0);
        assertEquals(1L, res.getId());
        assertEquals("Mua sắm tại combini", res.getTitle());
        assertEquals(10L, res.getTagId());
        assertEquals("Mua sắm (Shopping)", res.getTagName());
        assertEquals("ja", res.getLanguage());
    }

    @Test
    void findScriptsByCriteria_WithLanguageAndLevel_ReturnsFilteredScripts() {
        Tag topicTag = Tag.builder().id(10L).name("Mua sắm (Shopping)").build();
        Script s1 = Script.builder()
                .id(1L)
                .title("Mua sắm tại combini")
                .tag(topicTag)
                .language("ja")
                .level("n5")
                .targetDuration(60)
                .content("A: いらっしゃいませ\nB: ありがとう")
                .build();

        when(scriptRepository.findByCriteria(isNull(), isNull(), eq(false), any(), eq("ja"), eq("n5"), isNull(), isNull()))
                .thenReturn(List.of(s1));

        ScriptRequest request = ScriptRequest.builder()
                .language("ja")
                .level("N5")
                .build();

        List<ScriptResponse> responses = practiceService.findScriptsByCriteria(request);

        assertNotNull(responses);
        assertEquals(1, responses.size());
        assertEquals("n5", responses.get(0).getLevel());
    }

    @Test
    void findScriptsByCriteria_WithEmptyCriteria_ReturnsAllScripts() {
        Tag tagIntro = Tag.builder().id(20L).name("Giới thiệu bản thân").build();
        Script s1 = Script.builder()
                .id(2L)
                .title("Tự giới thiệu")
                .tag(tagIntro)
                .language("ja")
                .targetDuration(45)
                .build();

        when(scriptRepository.findByCriteria(isNull(), isNull(), eq(false), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(s1));

        List<ScriptResponse> responses = practiceService.findScriptsByCriteria(new ScriptRequest());

        assertEquals(1, responses.size());
        assertEquals("Giới thiệu bản thân", responses.get(0).getTagName());
    }

    @Test
    void getAvailableTopics_ReturnsOnlyTopicCategoryTags() {
        TagCategory topicCat = TagCategory.builder().id(1L).type(TagCategoryType.TOPIC).name("Chủ đề bài học").build();
        Tag t1 = Tag.builder().id(100L).name("Chủ đề 1").tagCategory(topicCat).build();

        when(tagRepository.findByTagCategoryType(TagCategoryType.TOPIC)).thenReturn(List.of(t1));

        List<Tag> topics = practiceService.getAvailableTopics();

        assertEquals(1, topics.size());
        assertEquals("Chủ đề 1", topics.get(0).getName());
        verify(tagRepository, times(1)).findByTagCategoryType(TagCategoryType.TOPIC);
    }

    @Test
    void getAvailableLanguages_ReturnsDistinctLanguages() {
        when(scriptRepository.findDistinctLanguages()).thenReturn(List.of("ja", "en"));

        List<String> languages = practiceService.getAvailableLanguages();

        assertEquals(2, languages.size());
        assertTrue(languages.contains("ja"));
        assertTrue(languages.contains("en"));
    }

    @Test
    void getTopicsWithScriptCount_ReturnsAggregatedData() {
        TopicWithCountDTO t1 = TopicWithCountDTO.builder()
                .tagId(10L)
                .tagName("Mua sắm")
                .language("ja")
                .level("n5")
                .scriptCount(3L)
                .build();
        TopicWithCountDTO t2 = TopicWithCountDTO.builder()
                .tagId(20L)
                .tagName("Coffee")
                .language("en")
                .level("a1")
                .scriptCount(2L)
                .build();

        when(scriptRepository.findTopicsWithScriptCount()).thenReturn(List.of(t1, t2));

        List<TopicWithCountDTO> result = practiceService.getTopicsWithScriptCount();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Mua sắm", result.get(0).getTagName());
        assertEquals("n5", result.get(0).getLevel());
        assertEquals(3L, result.get(0).getScriptCount());
        assertEquals("en", result.get(1).getLanguage());
        assertEquals("a1", result.get(1).getLevel());
    }

    @Test
    void getTopicsWithCountAsJson_ReturnsValidJsonString() {
        TopicWithCountDTO t1 = TopicWithCountDTO.builder()
                .tagId(10L)
                .tagName("Mua sắm")
                .language("ja")
                .level("n5")
                .scriptCount(3L)
                .build();

        when(scriptRepository.findTopicsWithScriptCount()).thenReturn(List.of(t1));

        String json = practiceService.getTopicsWithCountAsJson();

        assertNotNull(json);
        assertTrue(json.contains("\"tagId\":10"));
        assertTrue(json.contains("\"tagName\":\"Mua sắm\""));
        assertTrue(json.contains("\"language\":\"ja\""));
        assertTrue(json.contains("\"level\":\"n5\""));
        assertTrue(json.contains("\"scriptCount\":3"));
    }
}
