package com.example.videocall_marching_language.exception;

public class SessionConflictException extends RuntimeException {
    private final ConflictType conflictType;

    public SessionConflictException(ConflictType conflictType, String message) {
        super(message);
        this.conflictType = conflictType;
    }

    public ConflictType getConflictType() {
        return conflictType;
    }

    public enum ConflictType {
        USER_HAS_ACTIVE_SESSION,
        PEER_HAS_ACTIVE_SESSION,
        SESSION_ALREADY_EXISTS,
        TERMINAL_STATE,
        RECONNECT_DEADLINE_PASSED,
        MATCH_TIMEOUT,
        MAX_DURATION_REACHED
    }
}
