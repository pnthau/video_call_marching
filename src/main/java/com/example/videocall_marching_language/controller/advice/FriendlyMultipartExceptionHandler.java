package com.example.videocall_marching_language.controller.advice;

import com.example.videocall_marching_language.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

import java.time.Instant;

@ControllerAdvice
public class FriendlyMultipartExceptionHandler {

    private static final String MESSAGE = "Ảnh tải lên vượt quá dung lượng cho phép. Vui lòng chọn ảnh nhỏ hơn hoặc bằng 5 MB.";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Object handleOversizedMultipart(MaxUploadSizeExceededException ignored, HttpServletRequest request) {
        return payloadTooLarge(request);
    }

    @ExceptionHandler(MultipartException.class)
    public Object handleMalformedMultipart(MultipartException ignored, HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse("MULTIPART_REQUEST_INVALID",
                            "Yêu cầu tải lên không hợp lệ.", Instant.now(), "/api/**"));
        }
        ModelAndView view = new ModelAndView("error/400");
        view.setStatus(HttpStatus.BAD_REQUEST);
        view.addObject("status", HttpStatus.BAD_REQUEST.value());
        view.addObject("message", "Yêu cầu tải lên không hợp lệ.");
        return view;
    }

    private Object payloadTooLarge(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse("PAYLOAD_TOO_LARGE", MESSAGE, Instant.now(), "/api/**"));
        }
        ModelAndView view = new ModelAndView("error/413");
        view.setStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        view.addObject("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        view.addObject("message", MESSAGE);
        return view;
    }
}
