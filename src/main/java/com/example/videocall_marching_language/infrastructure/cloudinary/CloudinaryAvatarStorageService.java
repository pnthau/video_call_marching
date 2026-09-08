package com.example.videocall_marching_language.infrastructure.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.InvalidAvatarException;
import com.example.videocall_marching_language.service.AvatarStorageService;
import com.example.videocall_marching_language.service.AvatarUploadResult;
import com.example.videocall_marching_language.validation.AvatarValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryAvatarStorageService implements AvatarStorageService {

    private final Cloudinary cloudinary;
    private final AvatarValidator avatarValidator;

    @Override
    public AvatarUploadResult upload(MultipartFile avatar) {
        avatarValidator.validate(avatar);
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    avatar.getBytes(),
                    ObjectUtils.asMap("folder", "videocall-marching/avatars", "resource_type", "image")
            );
            Object secureUrl = result.get("secure_url");
            Object publicId = result.get("public_id");
            if (secureUrl == null || publicId == null) {
                throw new AvatarUploadException("Cloudinary không trả về URL ảnh", null);
            }
            return new AvatarUploadResult(secureUrl.toString(), publicId.toString());
        } catch (InvalidAvatarException | AvatarUploadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AvatarUploadException("Không thể tải avatar lên Cloudinary", null);
        }
    }

    @Override
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            cloudinary.uploader().destroy(
                    publicId,
                    ObjectUtils.asMap("resource_type", "image", "invalidate", true)
            );
        } catch (IOException | RuntimeException exception) {
            log.warn("Không thể xóa avatar cũ trên Cloudinary");
        }
    }
}
