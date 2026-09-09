package com.example.videocall_marching_language.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ActuatorSecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void livenessIsPublicAndDoesNotDependOnDatabaseDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void readinessIsPublicAndIncludesDatabaseAndFlywayHealthInItsDecision() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void anonymousHealthCannotSeeComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"components\""))))
                .andExpect(content().string(not(containsString("MYSQL_PASSWORD"))))
                .andExpect(content().string(not(containsString("jdbc:mysql"))))
                .andExpect(content().string(not(containsString("client-secret"))));
    }

    @Test
    void authenticatedNonAdminCannotSeeHealthComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .with(user("learner@example.com").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"components\""))));
    }

    @Test
    void adminHealthMaySeeAuthorizedComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"components\"")));
    }

    @Test
    void nonAllowlistedActuatorEndpointsAreNotExposed() throws Exception {
        for (String endpoint : new String[]{
                "env", "configprops", "beans", "mappings", "heapdump", "threaddump",
                "loggers", "conditions", "scheduledtasks", "caches", "prometheus"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().isNotFound());
        }
    }
}
