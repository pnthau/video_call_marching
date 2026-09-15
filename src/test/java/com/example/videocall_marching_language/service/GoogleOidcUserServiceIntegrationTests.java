package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.repository.SocialAccountRepository;
import com.example.videocall_marching_language.service.impl.GoogleOidcUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class GoogleOidcUserServiceIntegrationTests {

    @Autowired
    private IUserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Test
    @Transactional
    public void testCreateUser() {
        GoogleOidcUserService service = new GoogleOidcUserService(userRepository, socialAccountRepository) {
            @Override
            protected OidcUser loadGoogleUser(OidcUserRequest userRequest) {
                OidcIdToken token = new OidcIdToken(
                        "token-value",
                        Instant.now(),
                        Instant.now().plusSeconds(300),
                        Map.of(
                                "sub", "google-sub-real-test",
                                "email", "real_test@example.com",
                                "email_verified", true,
                                "name", "Real Test User"
                        )
                );
                return new DefaultOidcUser(List.of(), token, "email");
            }
        };

        service.loadUser(null);
        
        User user = userRepository.findByEmail("real_test@example.com").orElseThrow();
        System.out.println("User created successfully: " + user.getId());
    }
}
