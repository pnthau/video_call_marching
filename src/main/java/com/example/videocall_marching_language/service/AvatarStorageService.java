package com.example.videocall_marching_language.service;

import org.springframework.web.multipart.MultipartFile;

public interface AvatarStorageService {
    AvatarUploadResult upload(MultipartFile avatar);

    void delete(String publicId);
}
