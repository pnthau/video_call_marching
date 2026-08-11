package com.example.videocall_marching_language.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneNumberNormalizerTests {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer();

    @Test
    void normalizeConvertsVietnameseLocalPhoneNumber() {
        assertEquals("+84912345678", normalizer.normalize("0912 345-678"));
    }

    @Test
    void normalizeKeepsValidInternationalPhoneNumber() {
        assertEquals("+84912345678", normalizer.normalize("+84912345678"));
    }

    @Test
    void normalizeRejectsInvalidPhoneNumber() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("12345"));
    }

    @Test
    void maskHidesSensitiveDigits() {
        assertEquals("+8491****678", normalizer.mask("+84912345678"));
    }
}
