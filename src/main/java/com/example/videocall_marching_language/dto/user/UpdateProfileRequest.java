package com.example.videocall_marching_language.dto.user;

import com.example.videocall_marching_language.enums.AIProvider;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Getter
@Setter
@NoArgsConstructor
public class UpdateProfileRequest {

    @NotBlank(message = "Tên hiển thị không được để trống")
    @Size(min = 2, max = 50, message = "Tên hiển thị phải có từ 2 đến 50 ký tự")
    private String username;

    private AIProvider provider;

    private String apiKey;

    private MultipartFile avatar;
}
