package com.example.videocall_marching_language.dto.admin;

import com.example.videocall_marching_language.enums.TagCategoryType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminTagCategoryForm {
    private Long id;

    @NotBlank(message = "Tên TagCategory không được để trống")
    @Size(max = 100, message = "Tên TagCategory không được vượt quá 100 ký tự")
    private String name;

    @NotNull(message = "Vui lòng chọn loại TagCategory")
    private TagCategoryType type;

    private boolean active = true;

    @Min(value = 0, message = "Thứ tự hiển thị không được âm")
    @Max(value = 9999, message = "Thứ tự hiển thị quá lớn")
    private int displayOrder = 0;

    public AdminTagCategoryForm(Long id, String name, TagCategoryType type, boolean active, int displayOrder) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.active = active;
        this.displayOrder = displayOrder;
    }
}
