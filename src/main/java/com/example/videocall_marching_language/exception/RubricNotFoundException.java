package com.example.videocall_marching_language.exception;

public class RubricNotFoundException extends RuntimeException {
    public RubricNotFoundException(Long id) {
        super("Không tìm thấy rubric với ID: " + id);
    }
}
