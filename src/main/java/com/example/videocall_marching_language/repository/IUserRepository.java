package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IUserRepository extends JpaRepository<User, Long> {
}
