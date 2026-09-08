package com.example.videocall_marching_language.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.InvalidAvatarException;
import com.example.videocall_marching_language.infrastructure.cloudinary.CloudinaryAvatarStorageService;
import com.example.videocall_marching_language.validation.AvatarValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CloudinaryAvatarStorageServiceTests {

    private final Cloudinary cloudinary = mock(Cloudinary.class);
    private final Uploader uploader = mock(Uploader.class);
    private final AvatarValidator avatarValidator = mock(AvatarValidator.class);
    private final CloudinaryAvatarStorageService storage =
            new CloudinaryAvatarStorageService(cloudinary, avatarValidator);

    @Test
    void upload_invokesValidatorBeforeAnyCloudinaryOperation() throws Exception {
        MultipartFile avatar = avatar("avatar.png", "image/png", new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", "https://cdn.test/avatar.png", "public_id", "server-id"));

        storage.upload(avatar);

        var order = inOrder(avatarValidator, cloudinary, uploader);
        order.verify(avatarValidator).validate(avatar);
        order.verify(cloudinary).uploader();
        order.verify(uploader).upload(any(byte[].class), anyMap());
    }

    @Test
    void upload_whenValidatorRejects_propagatesAndNeverCallsCloudinary() {
        MultipartFile avatar = avatar("avatar.png", "image/png", new byte[]{1});
        InvalidAvatarException rejection =
                new InvalidAvatarException("AVATAR_INVALID_CONTENT", "Nội dung avatar không hợp lệ");
        doThrow(rejection).when(avatarValidator).validate(avatar);

        assertEquals(rejection, assertThrows(InvalidAvatarException.class, () -> storage.upload(avatar)));
        verify(avatarValidator).validate(avatar);
        verifyNoInteractions(cloudinary, uploader);
    }

    @Test
    void upload_whenValid_returnsProviderIdentityAndUploadsExactlyOnce() throws Exception {
        MultipartFile avatar = avatar("../../avatar.png", "image/png", new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", "https://cdn.test/server-id", "public_id", "server-id"));

        var result = storage.upload(avatar);

        assertEquals("https://cdn.test/server-id", result.secureUrl());
        assertEquals("server-id", result.publicId());
        verify(cloudinary).uploader();
        verify(uploader).upload(any(byte[].class), anyMap());
    }

    @Test
    void upload_whenOriginalFilenameContainsTraversal_doesNotUseItAsStorageIdentity() throws Exception {
        String originalFilename = "../../avatar.png";
        MultipartFile avatar = avatar(originalFilename, "image/png", new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", "https://cdn.test/server-id", "public_id", "server-id"));

        storage.upload(avatar);

        verify(uploader).upload(any(byte[].class), org.mockito.ArgumentMatchers.argThat(options ->
                !options.toString().contains(originalFilename)
                        && !options.containsValue(originalFilename)
                        && !options.containsKey("public_id")));
    }

    @Test
    void upload_whenProviderFails_mapsToSanitizedAvatarUploadException() throws Exception {
        MultipartFile avatar = avatar("avatar.png", "image/png", new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenThrow(new IOException("raw provider response and secret"));

        AvatarUploadException exception = assertThrows(
                AvatarUploadException.class,
                () -> storage.upload(avatar)
        );

        assertEquals("Không thể tải avatar lên Cloudinary", exception.getMessage());
        assertFalse(exception.getMessage().contains("raw provider"));
    }

    @Test
    void upload_whenSecureUrlIsMissing_failsSafely() throws Exception {
        MultipartFile avatar = avatar("avatar.png", "image/png", new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("public_id", "server-id"));

        AvatarUploadException exception = assertThrows(
                AvatarUploadException.class,
                () -> storage.upload(avatar)
        );

        assertEquals("Cloudinary không trả về URL ảnh", exception.getMessage());
    }

    @Test
    void upload_whenPublicIdIsMissing_failsSafely() throws Exception {
        MultipartFile avatar = avatar("avatar.png", "image/png", new byte[]{1, 2, 3});
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", "https://cdn.test/server-id"));

        AvatarUploadException exception = assertThrows(
                AvatarUploadException.class,
                () -> storage.upload(avatar)
        );

        assertEquals("Cloudinary không trả về URL ảnh", exception.getMessage());
    }

    private MultipartFile avatar(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("avatar", filename, contentType, bytes);
    }
}
