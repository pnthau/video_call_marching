package com.example.videocall_marching_language.controller.advice;

import com.example.videocall_marching_language.dto.response.ApiErrorResponse;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FriendlyErrorControllerTests {

    private final FriendlyErrorController controller = new FriendlyErrorController(new DefaultErrorAttributes());

    @Test
    void mvcMissingRouteReturnsFriendly404ViewWithStatus() {
        MockHttpServletRequest request = errorRequest("/missing", 404);

        Object result = controller.error(request, new MockHttpServletResponse(), new ExtendedModelMap());

        ModelAndView view = assertInstanceOf(ModelAndView.class, result);
        assertEquals(404, view.getStatus().value());
        assertEquals("error/404", view.getViewName());
        assertFalse(view.getModel().toString().contains("/missing"));
    }

    @Test
    void apiMissingRouteReturnsSanitizedJson404() {
        MockHttpServletRequest request = errorRequest("/api/unknown?secret=value", 404);

        Object result = controller.error(request, new MockHttpServletResponse(), new ExtendedModelMap());

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(404, response.getStatusCode().value());
        ApiErrorResponse body = assertInstanceOf(ApiErrorResponse.class, response.getBody());
        assertEquals("RESOURCE_NOT_FOUND", body.code());
        assertEquals("/api/**", body.path());
    }

    private MockHttpServletRequest errorRequest(String uri, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri.substring(0, uri.indexOf('?') > 0 ? uri.indexOf('?') : uri.length()));
        return request;
    }
}
