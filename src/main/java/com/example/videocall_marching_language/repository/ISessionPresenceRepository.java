package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.SessionPresence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

public interface ISessionPresenceRepository extends JpaRepository<SessionPresence, Long> {

    @Query("SELECT p FROM SessionPresence p WHERE p.sessionId = :sessionId AND p.userId = :userId ORDER BY p.joinedAt DESC")
    List<SessionPresence> findBySessionIdAndUserIdOrderByJoinedAtDesc(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SessionPresence p WHERE p.sessionId = :sessionId AND p.userId = :userId AND p.leftAt IS NULL")
    Optional<SessionPresence> findOpenIntervalForUpdate(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Query("SELECT p FROM SessionPresence p WHERE p.sessionId = :sessionId AND p.leftAt IS NULL")
    List<SessionPresence> findOpenIntervalsBySessionId(@Param("sessionId") Long sessionId);

    @Query("SELECT p FROM SessionPresence p WHERE p.sessionId = :sessionId AND p.leftAt IS NOT NULL ORDER BY p.joinedAt")
    List<SessionPresence> findClosedIntervalsBySessionId(@Param("sessionId") Long sessionId);

    @Query("SELECT p FROM SessionPresence p WHERE p.sessionId = :sessionId AND p.userId = :userId AND p.leftAt IS NOT NULL ORDER BY p.joinedAt")
    List<SessionPresence> findClosedIntervalsBySessionIdAndUserId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Query("SELECT COUNT(p) FROM SessionPresence p WHERE p.sessionId = :sessionId AND p.leftAt IS NULL")
    long countOpenIntervalsBySessionId(@Param("sessionId") Long sessionId);
}