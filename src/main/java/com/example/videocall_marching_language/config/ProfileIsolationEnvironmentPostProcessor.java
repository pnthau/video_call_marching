package com.example.videocall_marching_language.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Rejects migration/serving profile combinations before Flyway auto-configuration. */
public final class ProfileIsolationEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        String configured = environment.getProperty("SPRING_PROFILES_ACTIVE");
        if (configured != null) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(profile -> !profile.isEmpty())
                    .forEach(profiles::add);
        }
        if (profiles.contains("migration")
                && (profiles.contains("prod") || profiles.contains("cloudrun"))) {
            throw new IllegalStateException("Invalid production configuration: incompatible profiles");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
