package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.TagOptionDTO;
import com.example.videocall_marching_language.dto.audiolesson.AudioLessonDTO;
import com.example.videocall_marching_language.dto.audiolesson.AudioLessonRequest;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.service.audiolesson.AudioLessonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/practice/audio-lessons")
public class AudioLessonController {

    private final AudioLessonService audioLessonService;
    private final ITagRepository tagRepository;
    private final IUserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public String listAudioLessons(
            AudioLessonRequest request,
            @RequestParam(value = "autoOpen", required = false) Boolean autoOpen,
            Authentication authentication,
            Model model) {

        if (authentication != null && authentication.getName() != null) {
            userService.findByEmail(authentication.getName()).ifPresent(u -> {
                model.addAttribute("currentUserId", u.getId());
                model.addAttribute("currentUserEmail", u.getEmail());
            });
        }

        List<AudioLessonDTO> lessons = audioLessonService.findLessonsByCriteria(request);
        List<TopicWithCountDTO> topicsWithCount = audioLessonService.getTopicsWithAudioLessonCount();
        String topicsWithCountJson = audioLessonService.getTopicsWithCountAsJson();
        List<Tag> availableTopics = tagRepository.findByTagCategoryType(TagCategoryType.TOPIC);
        List<String> availableLanguages = audioLessonService.getAvailableLanguages();

        String selectedTopicName = null;
        if (request.getTagId() != null) {
            selectedTopicName = topicsWithCount.stream()
                    .filter(t -> t.getTagId().equals(request.getTagId()))
                    .map(TopicWithCountDTO::getTagName)
                    .findFirst()
                    .orElseGet(() -> availableTopics.stream()
                            .filter(t -> t.getId().equals(request.getTagId()))
                            .map(Tag::getName)
                            .findFirst()
                            .orElse(null));
        }

        boolean shouldOpenModal = Boolean.TRUE.equals(autoOpen) || !request.hasFilterCriteria();

        String selectedLevel = (request.getLevel() != null && !request.getLevel().isBlank() && !"all".equalsIgnoreCase(request.getLevel()))
                ? request.getLevel().toUpperCase()
                : null;

        List<TagOptionDTO> topicTags = tagRepository.findAllForActiveCategories().stream()
                .filter(t -> t.getTagCategory() != null && t.getTagCategory().getType() == TagCategoryType.TOPIC)
                .map(t -> new TagOptionDTO(t.getId(), t.getName(), t.getTagCategory().getType().name()))
                .toList();

        model.addAttribute("lessons", lessons);
        model.addAttribute("criteria", request);
        model.addAttribute("availableTopics", availableTopics);
        model.addAttribute("availableLanguages", availableLanguages);
        model.addAttribute("topicsWithCount", topicsWithCount);
        model.addAttribute("topicsWithCountJson", topicsWithCountJson);
        model.addAttribute("selectedTopicName", selectedTopicName);
        model.addAttribute("selectedLevel", selectedLevel);
        model.addAttribute("shouldOpenModal", shouldOpenModal);
        model.addAttribute("topicTags", topicTags);

        return "users/audio_lessons/list";
    }

    @GetMapping("/{id}")
    public String learnAudioLesson(
            @PathVariable Long id,
            Authentication authentication,
            Model model) {

        if (authentication != null && authentication.getName() != null) {
            userService.findByEmail(authentication.getName()).ifPresent(u -> {
                model.addAttribute("currentUserId", u.getId());
            });
        }

        AudioLessonDTO lesson = audioLessonService.getLessonById(id);
        model.addAttribute("lesson", lesson);
        try {
            model.addAttribute("lessonJson", objectMapper.writeValueAsString(lesson));
        } catch (Exception e) {
            log.error("Lỗi serialize lessonJson: {}", e.getMessage());
            model.addAttribute("lessonJson", "{}");
        }

        return "users/audio_lessons/learn";
    }

    @GetMapping("/{id}/roleplay")
    public String startRoleplayFromAudioLesson(@PathVariable Long id) {
        Long scriptId = audioLessonService.getOrCreateRoleplayScript(id);
        return "redirect:/practice/scripts/" + scriptId;
    }
}
