package com.example.videocall_marching_language.config.observability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.health.contributor.Status;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywayHealthIndicatorTests {

    @Test
    void validationFailureProducesSanitizedDownHealth() {
        Flyway flyway = mock(Flyway.class);
        doThrow(new IllegalStateException("migration-secret"))
                .when(flyway).validate();

        var result = new FlywayHealthIndicator(flyway).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).doesNotContainKey("exception");
        assertThat(result.getDetails()).doesNotContainKey("message");
    }

    @Test
    void databaseConnectionFailureProducesDownHealthWithoutRawException() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database-secret"));

        var result = new DataSourceHealthIndicator(dataSource).health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).doesNotContainValue("database-secret");
    }
}
