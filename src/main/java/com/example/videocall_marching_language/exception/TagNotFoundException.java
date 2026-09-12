package com.example.videocall_marching_language.exception;

public class TagNotFoundException extends RuntimeException {
    public TagNotFoundException(Long id) {
        super("Không tìm thấy Tag với ID: " + id);
    }
}
