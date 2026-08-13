package com.example.videocall_marching_language.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MatchRequestDTO {
    private Long userId;
    private String tagKey;
}
