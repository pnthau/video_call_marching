package com.example.videocall_marching_language.service;

import org.springframework.stereotype.Component;

import java.util.function.ToIntFunction;

@Component
public class AgoraUidPairGenerator {
    public UidPair generate(Long user1Id, Long user2Id) {
        return generate(user1Id, user2Id, this::stablePositiveUid);
    }

    UidPair generate(Long user1Id, Long user2Id, ToIntFunction<Long> candidateGenerator) {
        if (user1Id == null || user2Id == null || user1Id.equals(user2Id)) {
            throw new IllegalArgumentException("Agora UID assignment requires two different user IDs");
        }
        int user1Uid = requirePositive(candidateGenerator.applyAsInt(user1Id));
        int user2Uid = requirePositive(candidateGenerator.applyAsInt(user2Id));
        if (user1Uid == user2Uid) {
            user2Uid = user1Uid == Integer.MAX_VALUE ? 1 : user1Uid + 1;
        }
        return new UidPair(user1Uid, user2Uid);
    }

    private int stablePositiveUid(Long userId) {
        long hash = userId * 0x9e3779b97f4a7c15L;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        int uid = (int) (hash & 0x7fffffffL);
        return uid == 0 ? 1 : uid;
    }

    private int requirePositive(int uid) {
        if (uid <= 0) throw new IllegalStateException("Agora UID candidate must be positive");
        return uid;
    }

    public record UidPair(int user1Uid, int user2Uid) { }
}
