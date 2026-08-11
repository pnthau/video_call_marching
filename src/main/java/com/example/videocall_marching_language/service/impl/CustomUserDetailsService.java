package com.example.videocall_marching_language.service.impl;

import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserStatus;
import com.example.videocall_marching_language.repository.UserRepository;
import com.example.videocall_marching_language.utils.PhoneNumberNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        final String normalizedPhoneNumber;
        try {
            normalizedPhoneNumber = phoneNumberNormalizer.normalize(phoneNumber);
        } catch (IllegalArgumentException exception) {
            throw new UsernameNotFoundException("Thông tin đăng nhập không hợp lệ");
        }

        User user = userRepository.findByPhoneNumber(normalizedPhoneNumber)
                .orElseThrow(() -> new UsernameNotFoundException("Thông tin đăng nhập không hợp lệ"));

        return org.springframework.security.core.userdetails.User.withUsername(user.getPhoneNumber())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(user.getStatus() == UserStatus.DISABLED)
                .build();
    }
}
