package com.example.videocall_marching_language.config.security;

import com.example.videocall_marching_language.service.impl.GoogleOidcUserService;
import com.example.videocall_marching_language.ratelimit.InMemoryRateLimiter;
import com.example.videocall_marching_language.ratelimit.RateLimitFilter;
import com.example.videocall_marching_language.ratelimit.RateLimitRequestResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.util.Collection;

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
                .addFilterBefore(rateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    @Bean
    public RateLimitRequestResolver rateLimitRequestResolver() {
        return new RateLimitRequestResolver();
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
}
