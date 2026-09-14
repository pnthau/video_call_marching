package com.example.videocall_marching_language.ratelimit;

import java.util.Locale;
import java.util.Optional;

/** Resolves only the explicitly rate-limited HTTP operations. */
public final class RateLimitRequestResolver {

    public Optional<RateLimitPolicy> resolve(String method, String path, boolean avatarMultipart) {
        if (method == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        String verb = method.toUpperCase(Locale.ROOT);

        if ("GET".equals(verb) && "/api/sessions/active".equals(path)) {
            return Optional.of(RateLimitPolicy.SESSION_ACTIVE);
        }
        if ("GET".equals(verb) && path.matches("/api/sessions/[^/]+/token")) {
            return Optional.of(RateLimitPolicy.SESSION_TOKEN);
        }
        if ("POST".equals(verb) && path.matches("/api/sessions/[^/]+/(?:join|join-agora)")) {
            return Optional.of(RateLimitPolicy.SESSION_JOIN);
        }
        if ("POST".equals(verb) && path.matches("/api/sessions/[^/]+/(?:leave|leave-agora)")) {
            return Optional.of(RateLimitPolicy.SESSION_LEAVE);
        }
        if ("POST".equals(verb) && "/profile/edit".equals(path) && avatarMultipart) {
            return Optional.of(RateLimitPolicy.AVATAR_UPLOAD);
        }
        if (isMutation(verb) && (path.startsWith("/admin/rubrics/") || path.startsWith("/admin/users/"))) {
            return Optional.of(RateLimitPolicy.ADMIN_MUTATION);
        }
        return Optional.empty();
    }

    private boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }
}
