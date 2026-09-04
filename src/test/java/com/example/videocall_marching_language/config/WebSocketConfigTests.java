package com.example.videocall_marching_language.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTests {

    @Test
    void registersConfiguredAllowedOriginPatterns() {
        StompPrincipalInterceptor interceptor = mock(StompPrincipalInterceptor.class);
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        String[] allowedOrigins = {"http://localhost:*", "https://configured.example"};

        when(registry.addEndpoint("/ws")).thenReturn(registration);
        when(registration.setAllowedOriginPatterns(allowedOrigins)).thenReturn(registration);

        new WebSocketConfig(interceptor, allowedOrigins).registerStompEndpoints(registry);

        verify(registration).setAllowedOriginPatterns(allowedOrigins);
        verify(registration).withSockJS();
    }
}
