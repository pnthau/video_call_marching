package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.SocialAccount;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserStatus;
import com.example.videocall_marching_language.repository.SocialAccountRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.service.impl.GoogleOidcUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoogleOidcUserServiceTests {

    @Mock IUserRepository userRepository;
    @Mock SocialAccountRepository socialAccountRepository;

    @Test
    void firstLoginCreatesUserAndSocialAccount() {
        OidcUser google = googleUser(true);
        GoogleOidcUserService service = serviceReturning(google);
        when(socialAccountRepository.findByProviderAndProviderId("GOOGLE", "google-sub"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("learner@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OidcUser result = service.loadUser(mock(OidcUserRequest.class));

        verify(userRepository).save(any(User.class));
        verify(socialAccountRepository).save(any(SocialAccount.class));
        verify(userRepository).save(argThat(user -> "Học viên".equals(user.getUsername())));
        assertEquals("learner@example.com", result.getName());
    }

    @Test
    void repeatLoginDoesNotCreateDuplicateUser() {
        OidcUser google = googleUser(true);
        User user = User.builder().email("learner@example.com").status(UserStatus.ACTIVE).build();
        when(socialAccountRepository.findByProviderAndProviderId("GOOGLE", "google-sub"))
                .thenReturn(Optional.of(SocialAccount.builder().user(user).build()));

        serviceReturning(google).loadUser(mock(OidcUserRequest.class));

        verify(userRepository, never()).save(any());
        verify(socialAccountRepository, never()).save(any());
    }

    @Test
    void unverifiedEmailIsRejected() {
        assertThrows(OAuth2AuthenticationException.class,
                () -> serviceReturning(googleUser(false)).loadUser(mock(OidcUserRequest.class)));
        verify(userRepository, never()).save(any());
    }

    @Test
    void disabledAccountIsRejected() {
        User user = User.builder().email("learner@example.com").status(UserStatus.DISABLED).build();
        when(socialAccountRepository.findByProviderAndProviderId("GOOGLE", "google-sub"))
                .thenReturn(Optional.of(SocialAccount.builder().user(user).build()));

        OidcUser google = googleUser(true);
        assertThrows(OAuth2AuthenticationException.class,
                () -> serviceReturning(google).loadUser(mock(OidcUserRequest.class)));
    }
        












    private GoogleOidcUserService serviceReturning(OidcUser googleUser) {
        return new GoogleOidcUserService(userRepository, socialAccountRepository) {
            @Override
            protected OidcUser loadGoogleUser(OidcUserRequest userRequest) {
                return googleUser;
            }
        };
    }

    private OidcUser googleUser(boolean verified) {
        OidcIdToken token = new OidcIdToken(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(
                        "sub", "google-sub",
                        "email", "learner@example.com",
                        "email_verified", verified,
                        "name", "Học viên"
                )
        );
        return new DefaultOidcUser(List.of(), token, "email");
    }
}
