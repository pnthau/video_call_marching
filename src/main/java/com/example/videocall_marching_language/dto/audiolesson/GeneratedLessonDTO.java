package com.example.videocall_marching_language.dto.audiolesson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneratedLessonDTO {
    private String lessonTitle;
    private List<GeneratedSentenceDTO> sentences;
}
