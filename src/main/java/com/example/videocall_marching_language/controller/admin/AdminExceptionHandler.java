package com.example.videocall_marching_language.controller.admin;

import com.example.videocall_marching_language.exception.RubricNotFoundException;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(assignableTypes = AdminController.class)
public class AdminExceptionHandler {
    @ExceptionHandler({RubricNotFoundException.class, UserNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound() {
        return "admin/not-found";
    }
}
