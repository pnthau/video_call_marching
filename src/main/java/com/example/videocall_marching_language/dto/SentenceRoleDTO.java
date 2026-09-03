package com.example.videocall_marching_language.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SentenceRoleDTO {
    private String role;  // Chứa tên nhân vật (VD: "A", "B", "Khách hàng")
    private String text;  // Nội dung từng câu cắt ra
}
