package com.example.videocall_marching_language.controller.advice;

import com.example.videocall_marching_language.dto.response.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendlyMultipartExceptionHandlerTests {

    private static final String MESSAGE = "Ảnh tải lên vượt quá dung lượng cho phép. Vui lòng chọn ảnh nhỏ hơn hoặc bằng 5 MB.";
    private final FriendlyMultipartExceptionHandler handler = new FriendlyMultipartExceptionHandler();

    @Test
    void mvcMultipartParserFailureReturnsFriendly400View() {
        Object result = handler.handleMalformedMultipart(new MultipartException("parser rejected"), request("/profile/edit"));

        ModelAndView view = assertInstanceOf(ModelAndView.class, result);
        assertEquals(400, view.getStatus().value());
        assertEquals("error/400", view.getViewName());
        assertTrue(view.getModel().get("message").toString().contains("không hợp lệ"));
    }

    @Test
    void apiMultipartParserFailureReturnsSanitizedJson400() {
        Object result = handler.handleMalformedMultipart(new MultipartException("private parser detail"), request("/api/upload"));

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(400, response.getStatusCode().value());
        ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("MULTIPART_REQUEST_INVALID", body.code());
        assertEquals("/api/**", body.path());
        assertTrue(body.message().contains("không hợp lệ"));
    }

    @Test
    void onlySizeExceededMultipartMapsTo413() {
        Object result = handler.handleOversizedMultipart(
                new MaxUploadSizeExceededException(5L * 1024 * 1024), request("/api/upload"));

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(413, response.getStatusCode().value());
        ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("PAYLOAD_TOO_LARGE", body.code());
    }

    private MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
