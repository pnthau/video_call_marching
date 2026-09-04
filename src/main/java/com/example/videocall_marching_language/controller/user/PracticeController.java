package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.ScriptDTO;
import com.example.videocall_marching_language.dto.ScriptRequestDTO;
import com.example.videocall_marching_language.service.PracticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/practice")
public class PracticeController {

    private final PracticeService practiceService;

    @GetMapping("/scripts")
    public String getScriptsByTags(
            ScriptRequestDTO request,
            Model model) {
        List<ScriptDTO> scriptList = practiceService.findScriptsByTagAndPhase(request);
        model.addAttribute("scripts", scriptList);
        model.addAttribute("phase", request.getPhase());
        return "users/scripts/list";
    }

    @GetMapping("/scripts/{id}")
    public String getScriptById(
            @PathVariable int id,
            Model model) {
        ScriptDTO scriptDTO = practiceService.findScriptById(id);
        model.addAttribute("script", scriptDTO);
        return "users/scripts/learn";
    }
}
