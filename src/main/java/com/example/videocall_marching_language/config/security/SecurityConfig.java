package com.example.videocall_marching_language.config.security;

import com.example.videocall_marching_language.service.impl.GoogleOidcUserService;
import com.example.videocall_marching_language.ratelimit.InMemoryRateLimiter;
import com.example.videocall_marching_language.ratelimit.RateLimitFilter;
import com.example.videocall_marching_language.ratelimit.RateLimitRequestResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.http.MediaType;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;

import java.util.Collection;
import java.time.Instant;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final GoogleOidcUserService googleOidcUserService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RateLimitFilter rateLimitFilter) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/oauth2/**", "/login/oauth2/**", "/css/**", "/js/**", "/images/**", "/error")
                        .permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/profile/**", "/video-call", "/video-call/**", "/api/agora/**", "/api/sessions/**")
                        .authenticated()
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(googleOidcUserService)
                        )
                        .successHandler(customSuccessHandler())
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                )
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(accessDeniedHandler())
                )
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    public RateLimitRequestResolver rateLimitRequestResolver() {
        return new RateLimitRequestResolver();
    }

    @Bean
    @Primary
    public WebEndpointProperties actuatorWebEndpointProperties() {
        WebEndpointProperties properties = new WebEndpointProperties();
        properties.getExposure().setInclude(java.util.Set.of("health", "info"));
        return properties;
    }

    @Bean
    @Primary
    public HealthEndpointProperties actuatorHealthEndpointProperties() {
        HealthEndpointProperties properties = new HealthEndpointProperties();
        properties.setShowDetails(Show.WHEN_AUTHORIZED);
        properties.setRoles(java.util.Set.of("ADMIN"));

        HealthEndpointProperties.Group liveness = new HealthEndpointProperties.Group();
        liveness.setInclude(java.util.Set.of("livenessState"));
        liveness.setShowDetails(Show.NEVER);
        properties.getGroup().put("liveness", liveness);

        HealthEndpointProperties.Group readiness = new HealthEndpointProperties.Group();
        readiness.setInclude(java.util.Set.of("readinessState", "db", "flyway"));
        readiness.setShowDetails(Show.NEVER);
        properties.getGroup().put("readiness", readiness);
        return properties;
    }

    @Bean
    public RateLimitFilter rateLimitFilter(InMemoryRateLimiter inMemoryRateLimiter,
                                           RateLimitRequestResolver rateLimitRequestResolver) {
        return new RateLimitFilter(inMemoryRateLimiter, rateLimitRequestResolver);
    }

    @Bean
    public AuthenticationSuccessHandler customSuccessHandler() {
        return (request, response, authentication) -> {
            Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();

            String targetUrl = "/profile";

            for (GrantedAuthority authority : authorities) {
                if (authority.getAuthority().equals("ROLE_ADMIN")) {
                    targetUrl = "/admin";
                    break;
                }
            }

            response.sendRedirect(targetUrl);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> {
            if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"Bạn không có quyền truy cập tài nguyên này.\",\"timestamp\":\""
                        + Instant.now() + "\",\"path\":\"/api/**\"}");
                return;
            }
            response.sendError(403);
        };
    }
}
