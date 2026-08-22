package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface IUserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    Page<User> findByUsernameContainingIgnoreCase(
            String username,
            Pageable pageable
    );

    Page<User> findByEmailContainingIgnoreCase(
            String email,
            Pageable pageable
    );
    Page<User> findByUsernameContainingIgnoreCaseAndEmailContainingIgnoreCase(
            String username,
            String email,
            Pageable pageable
    );
    Page<User> findByRole(
            UserRole role,
            Pageable pageable
    );
    Page<User> findByRoleAndUsernameContainingIgnoreCase(
            UserRole role,
            String username,
            Pageable pageable
    );

    Page<User> findByRoleAndEmailContainingIgnoreCase(
            UserRole role,
            String email,
            Pageable pageable
    );

    Page<User> findByRoleAndUsernameContainingIgnoreCaseAndEmailContainingIgnoreCase(
            UserRole role,
            String username,
            String email,
            Pageable pageable
    );
}