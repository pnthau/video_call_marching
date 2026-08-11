package com.example.videocall_marching_language.utils;

import org.springframework.stereotype.Component;

@Component
public class PhoneNumberNormalizer {

    private static final String LOCAL_PHONE_PATTERN = "^0\\d{9}$";
    private static final String INTERNATIONAL_PHONE_PATTERN = "^\\+84\\d{9}$";

    public String normalize(String rawPhoneNumber) {
        if (rawPhoneNumber == null || rawPhoneNumber.isBlank()) {
            throw new IllegalArgumentException("Số điện thoại không được để trống");
        }

        String phoneNumber = rawPhoneNumber.trim().replaceAll("[\\s.\\-]", "");
        if (phoneNumber.matches(LOCAL_PHONE_PATTERN)) {
            return "+84" + phoneNumber.substring(1);
        }
        if (phoneNumber.matches(INTERNATIONAL_PHONE_PATTERN)) {
            return phoneNumber;
        }
        throw new IllegalArgumentException("Số điện thoại Việt Nam không hợp lệ");
    }

    public String mask(String normalizedPhoneNumber) {
        if (normalizedPhoneNumber == null || normalizedPhoneNumber.length() < 7) {
            return "***";
        }
        return normalizedPhoneNumber.substring(0, 5)
                + "****"
                + normalizedPhoneNumber.substring(normalizedPhoneNumber.length() - 3);
    }
}
