package com.example.videocall_marching_language.dto.admin;

import com.example.videocall_marching_language.enums.JapaneseLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminUserUpdateForm {
    @NotBlank(message = "Username không được để trống")
    @Size(min = 3, max = 50, message = "Username phải từ 3 đến 50 ký tự")
    private String username;

    @NotNull(message = "Vui lòng chọn trình độ")
    private JapaneseLevel currentLevel;

    public AdminUserUpdateForm(String username, JapaneseLevel currentLevel) {
        this.username = username;
        this.currentLevel = currentLevel;
    }
}
