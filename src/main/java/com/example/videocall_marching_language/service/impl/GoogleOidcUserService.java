package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.entity.SocialAccount;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.enums.UserStatus;
import com.example.videocall_marching_language.repository.SocialAccountRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final String PROVIDER = "GOOGLE";

    private final IUserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final OidcUserService delegate = new OidcUserService();

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        // Load Google user
        OidcUser googleUser = loadGoogleUser(userRequest);
        String providerId = requiredClaim(googleUser.getSubject(), "Tài khoản Google không có subject");
        String email = requiredClaim(googleUser.getEmail(), "Tài khoản Google không có email")
                .trim()
                .toLowerCase(Locale.ROOT);

        if (!Boolean.TRUE.equals(googleUser.getEmailVerified())) {
            throw authenticationError("unverified_google_email", "Email Google chưa được xác minh");
        }

        // Find existing user or create a new one
        final String finalProviderId = providerId;
        final String finalEmail = email;
        User user = socialAccountRepository.findByProviderAndProviderId(PROVIDER, finalProviderId)
                .map(SocialAccount::getUser)
                .orElseGet(() -> createUser(googleUser, finalProviderId, finalEmail));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw authenticationError("disabled_account", "Tài khoản đã bị vô hiệu hóa");
        }

        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                googleUser.getIdToken(),
                googleUser.getUserInfo(),
                "email"
        );
    }

    @Transactional
    protected OidcUser loadGoogleUser(OidcUserRequest userRequest) {
        return delegate.loadUser(userRequest);
    }

    private User createUser(OidcUser googleUser, String providerId, String email) {
        User user = userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .username(resolveUniqueUsername(googleUser, email))
                .email(email)
                .avatarUrl(googleUser.getPicture())
                .trustScore(0.0f)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build()));
        socialAccountRepository.save(SocialAccount.builder()
                .user(user)
                .provider(PROVIDER)
                .providerId(providerId)
                .build());
        return user;
    }

    private String resolveUsername(OidcUser googleUser, String email) {
        String fullName = googleUser.getFullName();
        String username = (fullName == null || fullName.isBlank())
                ? email.substring(0, email.indexOf('@'))
                : fullName.trim();
        username = username.replaceAll("\\s+", " ");
        if (username.length() > 50) {
            username = username.substring(0, 50).trim();
        }
        return username.length() >= 2 ? username : "user";
    }

    private String resolveUniqueUsername(OidcUser googleUser, String email) {
        String baseUsername = resolveUsername(googleUser, email);
        String candidate = baseUsername;
        int suffix = 2;
        while (userRepository.existsByUsername(candidate)) {
            String suffixValue = "-" + suffix++;
            candidate = baseUsername.substring(0, Math.min(baseUsername.length(), 50 - suffixValue.length()))
                    + suffixValue;
        }
        return candidate;
    }

    private String requiredClaim(String value, String message) {
        if (value == null || value.isBlank()) {
            throw authenticationError("invalid_google_account", message);
        }
        return value;
    }

    private OAuth2AuthenticationException authenticationError(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code), message);
    }
}
