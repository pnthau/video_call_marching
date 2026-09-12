package com.example.videocall_marching_language.dto.script;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptResponse {
    private Long id;
    private String title;
    private Long tagId;
    private String tagName;

    private List<SentenceRoleResponse> sentences;
    
    private String language;
    private String level;
    private Integer targetDuration;
    private String phoneticContent;
    private String meaningContent;
}
