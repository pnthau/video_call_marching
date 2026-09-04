package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.TagCategoryType;

import java.time.Instant;
import java.util.Set;

public class MatchingQueueEntry {

    private final Long userId;
    private final String username;
    private final JapaneseLevel level;
    private final Set<Long> tagIds;
    private final Instant enqueuedAt;
    private final String sessionId;

    public MatchingQueueEntry(Long userId, String username, JapaneseLevel level, Set<Long> tagIds, Instant enqueuedAt, String sessionId) {
        this.userId = userId;
        this.username = username;
        this.level = level;
        this.tagIds = tagIds;
        this.enqueuedAt = enqueuedAt;
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public JapaneseLevel getLevel() {
        return level;
    }

    public Set<Long> getTagIds() {
        return tagIds;
    }

    public Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean hasCommonTag(MatchingQueueEntry other) {
        return !this.tagIds.isEmpty() && !other.tagIds.isEmpty() &&
               this.tagIds.stream().anyMatch(other.tagIds::contains);
    }

    public int commonTagCount(MatchingQueueEntry other) {
        if (this.tagIds.isEmpty() || other.tagIds.isEmpty()) {
            return 0;
        }
        return (int) this.tagIds.stream().filter(other.tagIds::contains).count();
    }

    public boolean isAdjacentLevel(MatchingQueueEntry other) {
        int thisOrdinal = this.level.ordinal();
        int otherOrdinal = other.level.ordinal();
        return Math.abs(thisOrdinal - otherOrdinal) == 1;
    }

    public int compareByPriority(MatchingQueueEntry other) {
        int cmp = this.enqueuedAt.compareTo(other.enqueuedAt);
        if (cmp != 0) return cmp;
        return this.userId.compareTo(other.userId);
    }
}