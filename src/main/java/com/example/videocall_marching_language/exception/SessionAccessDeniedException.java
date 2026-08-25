package com.example.videocall_marching_language.exception;

public class SessionAccessDeniedException extends RuntimeException {
    public SessionAccessDeniedException(String message) {
        super(message);
    }
}