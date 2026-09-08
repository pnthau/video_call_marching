package com.example.videocall_marching_language.controller.advice;

import com.example.videocall_marching_language.controller.user.LearningSessionController;
import com.example.videocall_marching_language.dto.response.ApiErrorResponse;
import com.example.videocall_marching_language.exception.SessionAccessDeniedException;
import com.example.videocall_marching_language.exception.SessionConflictException;
import com.example.videocall_marching_language.exception.SessionNotFoundException;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@ControllerAdvice(assignableTypes = LearningSessionController.class)
@Slf4j
public class ApiExceptionHandler {

    private static final String API_SCOPE = "/api/sessions/**";

    @ExceptionHandler(SessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Không tìm thấy phiên học.");
    }

    @ExceptionHandler(SessionAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ApiErrorResponse> handleSessionAccessDenied(SessionAccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "SESSION_ACCESS_DENIED", "Bạn không có quyền truy cập phiên học này.");
    }

    @ExceptionHandler(SessionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiErrorResponse> handleSessionConflict(SessionConflictException ex) {
        return buildResponse(HttpStatus.CONFLICT, "SESSION_CONFLICT", "Trạng thái phiên học không cho phép thao tác này.");
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Không tìm thấy người dùng.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ignored
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "The request is invalid."
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected exception in learning-session API: {}", ex.getClass().getSimpleName());
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Đã xảy ra lỗi hệ thống.");
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String code,
            String message
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                code,
                message,
                Instant.now(),
                API_SCOPE
        );
        return ResponseEntity.status(status).body(body);
    }
}
