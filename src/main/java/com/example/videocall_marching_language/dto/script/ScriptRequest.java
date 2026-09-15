package com.example.videocall_marching_language.dto.script;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptRequest {
    private Long tagId;
    private String tag;
    private List<String> tags;
    private String language;
    private String level;
    private Integer phase;
    private Integer minDuration;
    private Integer maxDuration;

    public boolean hasFilterCriteria() {
        return tagId != null
                || (tag != null && !tag.isBlank())
                || (tags != null && !tags.isEmpty())
                || (language != null && !language.isBlank() && !"all".equalsIgnoreCase(language))
                || (level != null && !level.isBlank() && !"all".equalsIgnoreCase(level))
                || minDuration != null
                || maxDuration != null;
    }
}
