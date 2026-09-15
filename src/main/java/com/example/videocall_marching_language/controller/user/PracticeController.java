package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.LanguageOption;
import com.example.videocall_marching_language.dto.script.ScriptResponse;
import com.example.videocall_marching_language.dto.script.ScriptRequest;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.service.script.PracticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/practice")
public class PracticeController {

    private final PracticeService practiceService;

    @GetMapping("/scripts")
    public String getScriptsByTags(
            ScriptRequest request,
            @RequestParam(value = "autoOpen", required = false) Boolean autoOpen,
            Model model) {
        List<ScriptResponse> scriptList = practiceService.findScriptsByCriteria(request);
        List<TopicWithCountDTO> topicsWithCount = practiceService.getTopicsWithScriptCount();
        String topicsWithCountJson = practiceService.getTopicsWithCountAsJson();
        List<Tag> availableTopics = practiceService.getAvailableTopics();
        List<LanguageOption> availableLanguages = LanguageOption.fromCodes(practiceService.getAvailableLanguages());

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
        } else if (request.getTag() != null && !request.getTag().isBlank()) {
            selectedTopicName = request.getTag();
        } else if (request.getTags() != null && !request.getTags().isEmpty()) {
            selectedTopicName = String.join(", ", request.getTags());
        }

        boolean shouldOpenModal = Boolean.TRUE.equals(autoOpen) || !request.hasFilterCriteria();

        int phase = (request.getPhase() != null && request.getPhase() >= 1 && request.getPhase() <= 3)
                ? request.getPhase()
                : 1;

        String selectedLevel = (request.getLevel() != null && !request.getLevel().isBlank() && !"all".equalsIgnoreCase(request.getLevel()))
                ? request.getLevel().toUpperCase()
                : null;

        model.addAttribute("scripts", scriptList);
        model.addAttribute("criteria", request);
        model.addAttribute("phase", phase);
        model.addAttribute("selectedTopicName", selectedTopicName);
        model.addAttribute("selectedLevel", selectedLevel);
        model.addAttribute("availableTopics", availableTopics);
        model.addAttribute("availableLanguages", availableLanguages);
        model.addAttribute("topicsWithCount", topicsWithCount);
        model.addAttribute("topicsWithCountJson", topicsWithCountJson);
        model.addAttribute("shouldOpenModal", shouldOpenModal);

        return "users/scripts/list";
    }

    @GetMapping("/scripts/{id}")
    public String getScriptById(
            @PathVariable int id,
            @RequestParam(value = "phase", defaultValue = "1") int phase,
            Model model) {
        ScriptResponse scriptResponse = practiceService.findScriptById(id);
        model.addAttribute("script", scriptResponse);
        model.addAttribute("phase", phase);
        return "users/scripts/learn";
    }
}
