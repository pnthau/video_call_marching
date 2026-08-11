package com.example.videocall_marching_language.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.InvalidAvatarException;
import com.example.videocall_marching_language.service.AvatarStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CloudinaryAvatarStorageService implements AvatarStorageService {

    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp"
    );

    private final Cloudinary cloudinary;

    @Override
    public String upload(MultipartFile avatar) {
        validate(avatar);
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    avatar.getBytes(),
                    ObjectUtils.asMap("folder", "videocall-marching/avatars", "resource_type", "image")
            );
            Object secureUrl = result.get("secure_url");
            if (secureUrl == null) {
                throw new AvatarUploadException("Cloudinary không trả về URL ảnh", null);
            }
            return secureUrl.toString();
        } catch (InvalidAvatarException | AvatarUploadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AvatarUploadException("Không thể tải avatar lên Cloudinary", exception);
        }
    }

    private void validate(MultipartFile avatar) {
        if (avatar == null || avatar.isEmpty()) {
            throw new InvalidAvatarException("Vui lòng chọn ảnh avatar");
        }
        if (avatar.getSize() > MAX_AVATAR_SIZE) {
            throw new InvalidAvatarException("Avatar không được vượt quá 5 MB");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(avatar.getContentType())) {
            throw new InvalidAvatarException("Avatar chỉ hỗ trợ JPEG, PNG hoặc WebP");
        }
    }
}
