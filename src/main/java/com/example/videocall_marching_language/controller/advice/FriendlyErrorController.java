package com.example.videocall_marching_language.controller.advice;

import com.example.videocall_marching_language.dto.response.ApiErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@Controller
public class FriendlyErrorController implements ErrorController {

    private static final long MAX_MULTIPART_REQUEST_SIZE = 6L * 1024 * 1024;

    private final ErrorAttributes errorAttributes;

    public FriendlyErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public Object error(HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpStatus status = status(request, response);
        String path = safePath(request);
        if (path.startsWith("/api/")) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse(code(status), message(status), Instant.now(), apiScope(path)));
        }
        model.addAttribute("status", status.value());
        model.addAttribute("message", message(status));
        ModelAndView view = new ModelAndView("error/" + template(status), model.asMap(), status);
        return view;
    }

    private HttpStatus status(HttpServletRequest request, HttpServletResponse response) {
        if (hasMultipartFailure(request) || isKnownOversizedMultipartRequest(request)) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
        }
        HttpStatus responseStatus = HttpStatus.resolve(response.getStatus());
        if (responseStatus != null && responseStatus.isError()) {
            return responseStatus;
        }
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Number number) {
            return HttpStatus.resolve(number.intValue()) != null
                    ? HttpStatus.resolve(number.intValue()) : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private boolean isKnownOversizedMultipartRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")
                && request.getContentLengthLong() > MAX_MULTIPART_REQUEST_SIZE;
    }

    private boolean hasMultipartFailure(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Throwable cause = value instanceof Throwable throwable ? throwable : errorAttributes.getError(new ServletWebRequest(request));
        while (cause != null) {
            if (cause instanceof MaxUploadSizeExceededException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String safePath(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return value instanceof String path && path.startsWith("/") ? path : request.getRequestURI();
    }

    private String apiScope(String path) {
        if (path.startsWith("/api/sessions/")) return "/api/sessions/**";
        if (path.startsWith("/api/")) return "/api/**";
        return "/api/**";
    }

    private String template(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "404";
            case FORBIDDEN -> "403";
            case METHOD_NOT_ALLOWED -> "405";
            case PAYLOAD_TOO_LARGE -> "413";
            default -> "500";
        };
    }

    private String code(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case FORBIDDEN -> "ACCESS_DENIED";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
            default -> "INTERNAL_ERROR";
        };
    }

    private String message(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Không tìm thấy trang.";
            case FORBIDDEN -> "Bạn không có quyền truy cập trang này.";
            case METHOD_NOT_ALLOWED -> "Phương thức yêu cầu không được hỗ trợ.";
            case PAYLOAD_TOO_LARGE -> "Ảnh tải lên vượt quá dung lượng cho phép.";
            default -> "Đã xảy ra lỗi. Vui lòng thử lại sau.";
        };
    }
}
