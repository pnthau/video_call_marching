package com.example.videocall_marching_language.dto.admin;

import com.example.videocall_marching_language.enums.JapaneseLevel;
import jakarta.validation.constraints.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRequestDTO {

    private Long id;

    @NotBlank(message = "Username không được để trống")
    @Size(min = 3, max = 50, message = "Username phải từ 3 đến 50 ký tự")
    private String username;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng chuẩn (ví dụ: user@example.com)")
    private String email;

    @NotNull(message = "Vui lòng chọn trình độ tiếng Nhật")
    private JapaneseLevel currentLevel;

    @Min(value = 0, message = "Trust Score không được nhỏ hơn 0")
    @Max(value = 10, message = "Trust Score không được lớn hơn 10")
    private float trustScore;
}
