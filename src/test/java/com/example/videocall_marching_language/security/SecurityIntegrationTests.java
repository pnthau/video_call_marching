package com.example.videocall_marching_language.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

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
    void regularUserCannotAccessAdminRoutes() throws Exception {
        mockMvc.perform(get("/admin/users").with(user("learner@example.com").roles("USER")))
                .andExpect(status().isForbidden());
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
}
