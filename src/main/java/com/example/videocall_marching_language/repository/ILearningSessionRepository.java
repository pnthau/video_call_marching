package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.enums.SessionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

public interface ILearningSessionRepository extends JpaRepository<LearningSession, Long> {

    Optional<LearningSession> findByChannelName(String channelName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LearningSession s WHERE s.id = :id")
    Optional<LearningSession> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT s FROM LearningSession s WHERE s.user1.id = :userId OR s.user2.id = :userId ORDER BY s.matchedAt DESC")
    List<LearningSession> findByUserIdOrderByMatchedAtDesc(@Param("userId") Long userId);

    @Query("SELECT s FROM LearningSession s WHERE (s.user1.id = :userId OR s.user2.id = :userId) ORDER BY s.matchedAt DESC")
    Page<LearningSession> findByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT s FROM LearningSession s WHERE s.channelName = :channelName AND (s.user1.id = :userId OR s.user2.id = :userId)")
    Optional<LearningSession> findByChannelNameAndParticipant(@Param("channelName") String channelName, @Param("userId") Long userId);

    List<LearningSession> findByStatusIn(List<SessionStatus> statuses);

    @Query("SELECT s FROM LearningSession s WHERE (s.user1.id = :userId OR s.user2.id = :userId) AND s.status IN :statuses")
    List<LearningSession> findByUserIdAndStatusIn(@Param("userId") Long userId, @Param("statuses") List<SessionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LearningSession s WHERE (s.user1.id = :userId OR s.user2.id = :userId) AND s.status IN :statuses")
    Optional<LearningSession> findActiveSessionByUserIdWithLock(@Param("userId") Long userId, @Param("statuses") List<SessionStatus> statuses);

    boolean existsByUser1IdAndUser2IdAndStatusIn(Long user1Id, Long user2Id, List<SessionStatus> statuses);

    @Query("SELECT s FROM LearningSession s WHERE (s.user1.id = :userId1 AND s.user2.id = :userId2 OR s.user1.id = :userId2 AND s.user2.id = :userId1) AND s.status IN :statuses")
    Optional<LearningSession> findActiveSessionBetweenUsers(@Param("userId1") Long userId1, @Param("userId2") Long userId2, @Param("statuses") List<SessionStatus> statuses);

    @Query("SELECT s FROM LearningSession s WHERE s.status IN :activeStatuses AND (s.reconnectDeadline IS NOT NULL AND s.reconnectDeadline < :now)")
    List<LearningSession> findSessionsPastReconnectDeadline(@Param("activeStatuses") List<SessionStatus> activeStatuses, @Param("now") LocalDateTime now);

    @Query("SELECT s FROM LearningSession s WHERE s.status IN :activeStatuses AND s.startedAt IS NOT NULL AND s.startedAt <= :deadline")
    List<LearningSession> findSessionsPastMaxDuration(@Param("activeStatuses") List<SessionStatus> activeStatuses, @Param("deadline") LocalDateTime deadline);

    @Query("SELECT s FROM LearningSession s WHERE s.status = :matchedStatus AND s.matchedAt <= :deadline")
    List<LearningSession> findMatchedSessionsPastTimeout(@Param("matchedStatus") SessionStatus matchedStatus, @Param("deadline") LocalDateTime deadline);

    @Query("SELECT s FROM LearningSession s WHERE s.status IN :activeStatuses AND s.reconnectDeadline IS NULL AND s.startedAt IS NOT NULL")
    List<LearningSession> findInProgressSessionsWithoutReconnectDeadline(@Param("activeStatuses") List<SessionStatus> activeStatuses);
}