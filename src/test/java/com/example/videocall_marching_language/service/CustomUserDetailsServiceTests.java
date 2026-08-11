package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.enums.UserStatus;
import com.example.videocall_marching_language.repository.UserRepository;
import com.example.videocall_marching_language.service.impl.CustomUserDetailsService;
import com.example.videocall_marching_language.utils.PhoneNumberNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTests {

    @Mock
    private UserRepository userRepository;

    @Test
    void loadUserNormalizesPhoneAndMapsRole() {
        User user = User.builder()
                .phoneNumber("+84912345678")
                .passwordHash("hash")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.findByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));
        CustomUserDetailsService service = new CustomUserDetailsService(
                userRepository, new PhoneNumberNormalizer()
        );

        UserDetails details = service.loadUserByUsername("0912-345-678");

        assertEquals("+84912345678", details.getUsername());
        assertEquals("ROLE_USER", details.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void loadUserDisablesBlockedAccount() {
        User user = User.builder()
                .phoneNumber("+84912345678")
                .passwordHash("hash")
                .status(UserStatus.DISABLED)
                .build();
        when(userRepository.findByPhoneNumber("+84912345678")).thenReturn(Optional.of(user));
        CustomUserDetailsService service = new CustomUserDetailsService(
                userRepository, new PhoneNumberNormalizer()
        );

        assertFalse(service.loadUserByUsername("+84912345678").isEnabled());
    }

    @Test
    void loadUserReturnsGenericErrorForInvalidPhone() {
        CustomUserDetailsService service = new CustomUserDetailsService(
                userRepository, new PhoneNumberNormalizer()
        );

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("invalid"));
    }
}
