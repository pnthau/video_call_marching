package com.example.videocall_marching_language.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import com.example.videocall_marching_language.ratelimit.RateLimitFilter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Test
    void guestIsRedirectedFromProfileToLogin() throws Exception {
        mockMvc.perform(get("/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void guestIsRedirectedFromVideoCallToLogin() throws Exception {
        mockMvc.perform(get("/video-call"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void guestIsRedirectedFromLearningSessionApiToLogin() throws Exception {
        mockMvc.perform(get("/api/sessions/1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void authenticatedUserCannotUseLegacyAgoraTokenEndpoint() throws Exception {
        mockMvc.perform(get("/api/agora/token")
                        .param("channelName", "client-controlled-channel")
                        .param("uid", "123")
                        .with(user("learner@example.com").roles("USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void regularUserCannotAccessAdminRoutes() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("learner@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestIsRedirectedFromAdminAndAdminCanAccess() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/admin").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void adminPostRequiresCsrfAndWorksWithCsrf() throws Exception {
        mockMvc.perform(post("/admin/rubrics/toggle/1").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/rubrics/toggle/999999")
                        .with(user("admin@example.com").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyRubricOperationsAreNotExposed() throws Exception {
        mockMvc.perform(get("/admin/rubrics/1/edit")
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/rubrics/1/edit")
                        .with(user("admin@example.com").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/rubrics/1/toggle")
                        .with(user("admin@example.com").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutRequiresCsrfAndRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/logout")
                        .with(user("learner@example.com").roles("USER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/logout").with(user("learner@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedSessionActiveQuotaReturnsJson429AfterThirtyRequests() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/api/sessions/active")
                    .with(user("quota@example.com").roles("USER")));
        }
        mockMvc.perform(get("/api/sessions/active")
                        .with(user("quota@example.com").roles("USER")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/sessions/**")));
    }

    @Test
    void springContextExposesRegisteredRateLimitFilter() {
        org.junit.jupiter.api.Assertions.assertNotNull(rateLimitFilter);
    }

    @Test
    void sessionTokenJoinAndLeavePoliciesExhaustWithStaticJson429() throws Exception {
        assertSessionPolicy429("/api/sessions/71/token", "GET", 10);
        assertSessionPolicy429("/api/sessions/71/join", "POST", 10);
        assertSessionPolicy429("/api/sessions/71/leave", "POST", 10);
    }

    @Test
    void avatarPolicyRequiresMultipartAndAdminMutationIsSeparateFromReads() throws Exception {
        for (int i = 0; i < 5; i++) {
            try {
                mockMvc.perform(multipart("/profile/edit")
                        .file(new MockMultipartFile("avatar", "avatar.png", "image/png", new byte[]{1}))
                        .with(user("avatar-quota@example.com").roles("USER")).with(csrf()));
            } catch (Exception ignored) {
                // Controller-level user lookup is outside this filter contract.
            }
        }
        mockMvc.perform(multipart("/profile/edit")
                        .file(new MockMultipartFile("avatar", "avatar.png", "image/png", new byte[]{1}))
                        .with(user("avatar-quota@example.com").roles("USER")).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/profile/edit")));

        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/admin/rubrics/toggle/999999")
                    .with(user("admin-quota@example.com").roles("ADMIN")).with(csrf()));
        }
        mockMvc.perform(post("/admin/rubrics/toggle/999999")
                        .with(user("admin-quota@example.com").roles("ADMIN")).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/**")));
        mockMvc.perform(get("/admin").with(user("admin-quota@example.com").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private void assertSessionPolicy429(String path, String method, int capacity) throws Exception {
        for (int i = 0; i < capacity; i++) {
            if ("GET".equals(method)) {
                mockMvc.perform(get(path).with(user("session-policy-" + path + "@example.com").roles("USER")));
            } else {
                mockMvc.perform(post(path).with(user("session-policy-" + path + "@example.com").roles("USER"))
                        .with(csrf()));
            }
        }
        var request = "GET".equals(method) ? get(path) : post(path).with(csrf());
        mockMvc.perform(request.with(user("session-policy-" + path + "@example.com").roles("USER")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/sessions/**")));
    }
}
