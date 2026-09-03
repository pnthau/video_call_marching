package com.example.videocall_marching_language.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UidGenerationTests {
    private final AgoraUidPairGenerator generator = new AgoraUidPairGenerator();

    @Test
    void pairAssignmentIsDeterministicPositiveAndDistinctAtLongMaxValue() {
        AgoraUidPairGenerator.UidPair first = generator.generate(Long.MAX_VALUE, Long.MIN_VALUE);
        AgoraUidPairGenerator.UidPair second = generator.generate(Long.MAX_VALUE, Long.MIN_VALUE);

        assertEquals(first, second);
        assertTrue(first.user1Uid() > 0);
        assertTrue(first.user2Uid() > 0);
        assertNotEquals(first.user1Uid(), first.user2Uid());
    }

    @Test
    void pairAssignmentResolvesForcedCollisionWithoutLongArithmetic() {
        AgoraUidPairGenerator.UidPair pair = generator.generate(
                Long.MAX_VALUE, 7L, ignored -> Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, pair.user1Uid());
        assertEquals(1, pair.user2Uid());
    }

    @Test
    void pairAssignmentHandlesLongMinValue() {
        AgoraUidPairGenerator.UidPair pair = generator.generate(Long.MIN_VALUE, Long.MIN_VALUE + 1);

        assertTrue(pair.user1Uid() > 0);
        assertTrue(pair.user2Uid() > 0);
        assertNotEquals(pair.user1Uid(), pair.user2Uid());
    }

    @Test
    void pairAssignmentIsDeterministicForSameInput() {
        for (int i = 0; i < 100; i++) {
            long id1 = (long) (i * 1000 + 1);
            long id2 = (long) (i * 1000 + 2);
            AgoraUidPairGenerator.UidPair first = generator.generate(id1, id2);
            AgoraUidPairGenerator.UidPair second = generator.generate(id1, id2);
            assertEquals(first, second, "UID pair must be deterministic for ids " + id1 + ", " + id2);
        }
    }

    @Test
    void pairAssignmentProducesDistinctUidsForDifferentUsers() {
        Set<Integer> allUids = new HashSet<>();
        for (long i = 1; i <= 10000; i++) {
            for (long j = i + 1; j <= i + 10; j++) {
                AgoraUidPairGenerator.UidPair pair = generator.generate(i, j);
                assertTrue(pair.user1Uid() > 0, "user1Uid must be positive for " + i);
                assertTrue(pair.user2Uid() > 0, "user2Uid must be positive for " + j);
                assertNotEquals(pair.user1Uid(), pair.user2Uid(), "UIDs must be distinct for " + i + ", " + j);
                allUids.add(pair.user1Uid());
                allUids.add(pair.user2Uid());
            }
        }
        // Verify good distribution - at least 50% unique values for 20000 generated UIDs
        assertTrue(allUids.size() > 10000, "UID distribution should have low collision rate");
    }

    @Test
    void pairAssignmentRejectsSameUserId() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(100L, 100L));
    }

    @Test
    void pairAssignmentRejectsNullUserId() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(1L, null));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null, null));
    }

    @Test
    void stablePositiveUidNeverReturnsZeroOrNegative() {
        for (long i = Long.MIN_VALUE; i < Long.MIN_VALUE + 10000; i++) {
            int uid = invokeStablePositiveUid(i);
            assertTrue(uid > 0, "UID must be positive for " + i + ", got " + uid);
        }
        for (long i = 0; i < 10000; i++) {
            int uid = invokeStablePositiveUid(i);
            assertTrue(uid > 0, "UID must be positive for " + i + ", got " + uid);
        }
        for (long i = Long.MAX_VALUE - 10000; i < Long.MAX_VALUE; i++) {
            int uid = invokeStablePositiveUid(i);
            assertTrue(uid > 0, "UID must be positive for " + i + ", got " + uid);
        }
    }

    @Test
    void stablePositiveUidHandlesLongMaxValue() {
        int uid = invokeStablePositiveUid(Long.MAX_VALUE);
        assertTrue(uid > 0);
        assertTrue(uid <= Integer.MAX_VALUE);
    }

    @Test
    void stablePositiveUidHandlesLongMinValue() {
        int uid = invokeStablePositiveUid(Long.MIN_VALUE);
        assertTrue(uid > 0);
        assertTrue(uid <= Integer.MAX_VALUE);
    }

    private int invokeStablePositiveUid(Long userId) {
        try {
            java.lang.reflect.Method method = AgoraUidPairGenerator.class.getDeclaredMethod("stablePositiveUid", Long.class);
            method.setAccessible(true);
            return (int) method.invoke(generator, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
