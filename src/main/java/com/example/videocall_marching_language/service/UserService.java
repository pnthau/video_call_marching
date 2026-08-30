package com.example.videocall_marching_language.service;


import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.entity.User;

import java.util.Optional;

public interface UserService {
    UserProfileResponse getCurrentProfile(String email);

    UserProfileResponse updateCurrentProfile(String email, UpdateProfileRequest request);

    Optional<User> findByEmail(String email);
}
