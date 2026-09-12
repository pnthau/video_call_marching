package com.example.videocall_marching_language.controller.admin;

import com.example.videocall_marching_language.exception.RubricNotFoundException;
import com.example.videocall_marching_language.exception.TagCategoryDeleteException;
import com.example.videocall_marching_language.exception.TagCategoryNotFoundException;
import com.example.videocall_marching_language.exception.TagNotFoundException;
import com.example.videocall_marching_language.exception.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice(assignableTypes = {AdminController.class, AdminTagController.class})
public class AdminExceptionHandler {
    @ExceptionHandler({RubricNotFoundException.class, UserNotFoundException.class, TagNotFoundException.class, TagCategoryNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound() {
        return "admin/not-found";
    }

    @ExceptionHandler(TagCategoryDeleteException.class)
    public String categoryDeleteConflict() {
        return "redirect:/admin/tags?categoryDeleteError";
    }
}
