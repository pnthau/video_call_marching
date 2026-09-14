package com.example.videocall_marching_language.exception;

public class InvalidAvatarException extends RuntimeException {

    private final String errorCode;

    public InvalidAvatarException(String message) {
        this(null, message);
    }

    public InvalidAvatarException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
