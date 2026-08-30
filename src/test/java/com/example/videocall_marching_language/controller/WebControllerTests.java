package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.controller.user.WebController;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.Model;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class WebControllerTests {

    @Test
    void videoCallPageLoadsCategoryTypesAndTheirTags() {
        UserService userService = mock(UserService.class);
        ITagRepository tagRepository = mock(ITagRepository.class);
        MatchingProperties matchingProperties = new MatchingProperties();
        Model model = mock(Model.class);
        TagCategory topic = TagCategory.builder()
                .id(1L).name("Chủ đề bài học").type(TagCategoryType.TOPIC).build();
        List<Tag> tags = List.of(Tag.builder().id(10L).name("Giới thiệu bản thân").tagCategory(topic).build());
        when(tagRepository.findAllForActiveCategories()).thenReturn(tags);

        WebController controller = new WebController(userService, tagRepository, matchingProperties);
        ReflectionTestUtils.setField(controller, "agoraAppId", "test-app-id");

        String viewName = controller.showVideoCallPage(null, model);

        assertEquals("users/video_call", viewName);
        verify(model).addAttribute("agoraAppId", "test-app-id");
        verify(model).addAttribute(eq("tagCategoryTypes"), any(TagCategoryType[].class));
        verify(model).addAttribute("availableTags", List.of(
                new com.example.videocall_marching_language.dto.TagOptionDTO(
                        10L, "Giới thiệu bản thân", "TOPIC")));
        verify(model).addAttribute("adjacentLevelAfterSeconds", 120);
    }
}
