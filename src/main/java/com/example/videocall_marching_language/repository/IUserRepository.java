package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IUserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    List<User> findByRole(UserRole role);

    /**
     * Tìm kiếm người dùng theo Role, Username và Email linh hoạt trong 1 câu Query duy nhất.
     */
    @Query("SELECT u FROM User u WHERE u.role = :role AND " +
            "(:username IS NULL OR :username = '' OR LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%'))) AND " +
            "(:email IS NULL OR :email = '' OR LOWER(u.email) LIKE LOWER(CONCAT('%', :email, '%')))")
    Page<User> searchUsersByRole(
            @Param("role") UserRole role,
            @Param("username") String username,
            @Param("email") String email,
            Pageable pageable
    );
}