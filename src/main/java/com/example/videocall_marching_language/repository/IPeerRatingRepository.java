package com.example.videocall_marching_language.repository;

import com.example.videocall_marching_language.entity.PeerRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IPeerRatingRepository extends JpaRepository<PeerRating, Long> {
    boolean existsByRaterIdAndSessionId(Long raterId, Long sessionId);
}
