package com.example.videocall_marching_language.exception;

public class TagCategoryNotFoundException extends RuntimeException {
    public TagCategoryNotFoundException(Long id) {
        super("Không tìm thấy TagCategory với ID: " + id);
    }
}
