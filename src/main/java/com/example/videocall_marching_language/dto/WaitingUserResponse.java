package com.example.videocall_marching_language.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WaitingUserResponse {
    private Long userId;
    private String username;
    private String tagKey;
    private long joinedTimestamp;
}
