package com.example.videocall_marching_language.service;


import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;

public interface UserService {
    UserProfileResponse getCurrentProfile(String email);

    UserProfileResponse updateCurrentProfile(String email, UpdateProfileRequest request);
}
