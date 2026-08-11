package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.dto.user.RegisterRequest;
import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;

public interface UserService {
    UserProfileResponse register(RegisterRequest request);

    UserProfileResponse getCurrentProfile(String phoneNumber);

    UserProfileResponse updateCurrentProfile(String phoneNumber, UpdateProfileRequest request);
}
