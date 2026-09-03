package com.example.videocall_marching_language.dto;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptDTO {
    private Long id;
    private String title;

    private List<SentenceRoleDTO> sentences;
    
    private String language;
    private Integer targetDuration;
}
