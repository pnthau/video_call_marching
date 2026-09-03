package com.example.videocall_marching_language.dto;

import com.example.videocall_marching_language.entity.Tag;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScriptRequestDTO {
    private List<String> tags;
    private int phase;
}
