package com.example.videocall_marching_language.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminTagForm {
    private Long id;

    @NotBlank(message = "Tên Tag không được để trống")
    @Size(max = 100, message = "Tên Tag không được vượt quá 100 ký tự")
    private String name;

    @NotNull(message = "Vui lòng chọn TagCategory")
    private Long categoryId;

    public AdminTagForm(Long id, String name, Long categoryId) {
        this.id = id;
        this.name = name;
        this.categoryId = categoryId;
    }
}
