package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.service.impl.GoogleOidcUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.util.Collection;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final GoogleOidcUserService googleOidcUserService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize

                        .requestMatchers("/", "/landing", "/login", "/oauth2/**", "/login/oauth2/**", "/css/**", "/js/**", "/images/**", "/error")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/profile/**", "/video-call", "/video-call/**", "/api/agora/**", "/api/sessions/**", "/ai-call", "/api/ai-tutor/**", "/api/peer-ratings/**", "/api/practice/**", "/practice/**", "/api/audio-lessons/**")
                        .authenticated()
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(googleOidcUserService)
                        )
                        .successHandler(customSuccessHandler())
                        .failureHandler((request, response, exception) -> {
                            System.err.println("=== OAUTH2 LOGIN FAILED ===");
                            System.err.println("Error Message: " + exception.getMessage());
                            exception.printStackTrace();
                            System.err.println("===========================");
                            response.sendRedirect("/login?error");
                        })
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                );

        return http.build();
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
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(clientRegistration -> {
            OAuth2TokenValidator<Jwt> idTokenValidator = new OidcIdTokenValidator(clientRegistration);
            return new DelegatingOAuth2TokenValidator<>(idTokenValidator);
        });
        return factory;
    }
}
