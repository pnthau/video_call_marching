package com.example.videocall_marching_language.config.observability;

import org.flywaydb.core.Flyway;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports migration compatibility without exposing Flyway or database details.
 * Validation is deliberately read-only; migration execution remains startup-only.
 */
@Component("flywayHealthIndicator")
public class FlywayHealthIndicator implements HealthIndicator {

    private final Flyway flyway;

    public FlywayHealthIndicator(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public Health health() {
        try {
            flyway.validate();
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }
}
