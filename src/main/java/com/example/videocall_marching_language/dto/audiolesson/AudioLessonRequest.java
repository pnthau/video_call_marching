package com.example.videocall_marching_language.dto.audiolesson;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AudioLessonRequest {
    private Long tagId;
    private String language;
    private String level;
    private String focus; // all, dictation, grammar, shadowing

    public boolean hasFilterCriteria() {
        return tagId != null
                || (language != null && !language.isBlank() && !"all".equalsIgnoreCase(language))
                || (level != null && !level.isBlank() && !"all".equalsIgnoreCase(level))
                || (focus != null && !focus.isBlank() && !"all".equalsIgnoreCase(focus));
    }
}
