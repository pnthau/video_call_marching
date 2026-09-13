package com.example.videocall_marching_language.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.SpringApplication;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigurationValidatorTests {

    private static final String SECRET = "dummy-production-secret-value";

    @Test
    void validProductionConfigurationStartsValidatorContext() {
        try (AnnotationConfigApplicationContext context = contextWith(validEnvironment())) {
            assertDoesNotThrow(() -> context.getBean(ProductionConfigurationValidator.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://localhost:3306/videocall_test",
            "jdbc:mysql://api.localhost:3306/videocall_test",
            "jdbc:mysql://127.0.0.1:3306/videocall_test",
            "jdbc:mysql://127.44.12.9:3306/videocall_test",
            "jdbc:mysql://127.255.255.255:3306/videocall_test",
            "jdbc:mysql://[::1]:3306/videocall_test",
            "jdbc:mysql:///videocall_test",
            "jdbc:mysql://:3306/videocall_test",
            "jdbc:postgresql://localhost:5432/videocall_test",
            "jdbc:mysql://[invalid/videocall_test",
            "${SPRING_DATASOURCE_URL}"
    })
    void invalidProductionDataSourceHostFailsSanitized(String datasourceUrl) {
        MockEnvironment environment = validEnvironment().withProperty("spring.datasource.url", datasourceUrl);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: spring.datasource.url", failure.getMessage());
        assertFalse(failure.toString().contains(datasourceUrl));
        assertFalse(failure.toString().contains(SECRET));
    }

    @Test
    void composeStyleDatasourceHostnamePassesProductionContext() {
        MockEnvironment environment = validEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://mysql:3306/videocall_test");

        try (AnnotationConfigApplicationContext context = contextWith(environment)) {
            assertDoesNotThrow(() -> context.getBean(ProductionConfigurationValidator.class));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MYSQL_DATABASE", "MYSQL_USERNAME", "MYSQL_PASSWORD",
            "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "CLOUDINARY_CLOUD_NAME",
            "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET", "AGORA_APP_ID",
            "AGORA_APP_CERTIFICATE", "WEBSOCKET_ALLOWED_ORIGIN_PATTERNS",
            "SPRING_PROFILES_ACTIVE"
    })
    void eachRequiredProductionNameFailsContextStartupWithoutSecretLeak(String missingName) {
        MockEnvironment environment = validEnvironment();
        environment.withProperty(missingName, "");

        Exception failure = assertThrows(Exception.class, () -> {
            try (AnnotationConfigApplicationContext ignored = contextWith(environment)) {
                // Context refresh is expected to fail for the missing property.
            }
        });

        assertFalse(failure.toString().contains(SECRET));
        assertFalse(failure.toString().contains("jdbc:mysql://"));
    }

    @Test
    void productionPropertiesUseComposeMySqlAndSecureRuntimeDefaults() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application-prod.properties"));

        assertEquals("${PORT:${SERVER_PORT:8080}}", properties.getProperty("server.port"));
        assertEquals("0.0.0.0", properties.getProperty("server.address"));
        assertEquals("graceful", properties.getProperty("server.shutdown"));
        assertEquals("30s", properties.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
        assertEquals("framework", properties.getProperty("server.forward-headers-strategy"));
        assertEquals("true", properties.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("true", properties.getProperty("server.servlet.session.cookie.http-only"));
        assertEquals("lax", properties.getProperty("server.servlet.session.cookie.same-site"));
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", properties.getProperty("spring.flyway.enabled"));
        assertEquals("false", properties.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("${SPRING_DATASOURCE_URL:jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=false}",
                properties.getProperty("spring.datasource.url"));
        assertFalse(properties.getProperty("spring.datasource.url").contains("localhost"));
        assertFalse(properties.getProperty("app.websocket.allowed-origin-patterns").contains("localhost"));
        assertEquals("5", properties.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("1", properties.getProperty("spring.datasource.hikari.minimum-idle"));
        assertEquals("5000", properties.getProperty("spring.datasource.hikari.connection-timeout"));
        assertEquals("2000", properties.getProperty("spring.datasource.hikari.validation-timeout"));
    }

    @Test
    void migrationProfileEnablesFlywayOutsideServingProfile() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application-migration.properties"));

        assertEquals("true", properties.getProperty("spring.flyway.enabled"));
        assertEquals("none", properties.getProperty("spring.main.web-application-type"));
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("jdbc:mysql:///${MYSQL_DATABASE}", properties.getProperty("spring.datasource.url"));
        assertEquals("${MIGRATION_MYSQL_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("com.google.cloud.sql.mysql.SocketFactory",
                properties.getProperty("spring.datasource.hikari.data-source-properties.socketFactory"));
    }

    @Test
    void migrationCannotBeCombinedWithServingProfile() {
        MockEnvironment environment = migrationEnvironment();
        environment.setActiveProfiles("prod", "migration");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: incompatible profiles", failure.getMessage());
    }

    @Test
    void cloudRunCannotBeCombinedWithMigrationProfile() {
        MockEnvironment environment = migrationEnvironment();
        environment.setActiveProfiles("cloudrun", "migration");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: incompatible profiles", failure.getMessage());
    }

    @Test
    void migrationProfileRequiresDedicatedIdentityAndConnector() {
        MockEnvironment environment = migrationEnvironment();
        environment.withProperty("MIGRATION_MYSQL_PASSWORD", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Missing required production configuration: MIGRATION_MYSQL_PASSWORD", failure.getMessage());
    }

    @Test
    void developmentPropertiesRetainLocalWebSocketFallback() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        String origins = properties.getProperty("app.websocket.allowed-origin-patterns");
        assertEquals("${WEBSOCKET_ALLOWED_ORIGIN_PATTERNS:http://localhost:*,http://127.0.0.1:*}", origins);
    }

    @Test
    void cloudRunEnvironmentTakesPortAndPublicBaseUrlPrecedence() {
        MockEnvironment environment = cloudRunEnvironment()
                .withProperty("PORT", "8080")
                .withProperty("PUBLIC_BASE_URL", "https://staging.example.test")
                .withProperty("APP_BASE_URL", "https://legacy.example.test")
                .withProperty("spring.datasource.url", "jdbc:mysql:///${MYSQL_DATABASE}");

        assertDoesNotThrow(() -> new ProductionConfigurationValidator(environment).validate());
        assertEquals("8080", environment.resolvePlaceholders("${PORT:${SERVER_PORT:8080}}"));
    }

    @Test
    void cloudRunUsesServerPortOnlyAsFallback() {
        MockEnvironment environment = cloudRunEnvironment()
                .withProperty("SERVER_PORT", "9090");

        assertDoesNotThrow(() -> new ProductionConfigurationValidator(environment).validate());
        assertEquals("9090", environment.resolvePlaceholders("${PORT:${SERVER_PORT:8080}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "65536", "not-a-port"})
    void invalidCloudRunPortFailsWithoutEchoingValue(String port) {
        MockEnvironment environment = cloudRunEnvironment().withProperty("PORT", port);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: PORT", failure.getMessage());
        if (!port.isEmpty()) {
            assertFalse(failure.toString().contains(port));
        }
    }

    @Test
    void cloudRunPlainJdbcOverrideFailsClosed() {
        MockEnvironment environment = cloudRunEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://db.example:3306/videocall_test");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid Cloud Run configuration: connector datasource", failure.getMessage());
    }

    @Test
    void cloudRunRequiresConnectorIdentity() {
        MockEnvironment environment = cloudRunEnvironment();
        environment.withProperty("CLOUD_SQL_INSTANCE_CONNECTION_NAME", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Missing required production configuration: CLOUD_SQL_INSTANCE_CONNECTION_NAME",
                failure.getMessage());
    }

    @Test
    void cloudRunPropertiesDisableSqlLoggingAndWireConnector() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application-cloudrun.properties"));

        assertEquals("jdbc:mysql:///${MYSQL_DATABASE}", properties.getProperty("spring.datasource.url"));
        assertEquals("PUBLIC", properties.getProperty(
                "spring.datasource.hikari.data-source-properties.ipTypes"));
        assertEquals("OFF", properties.getProperty("logging.level.org.hibernate.SQL"));
        assertEquals("OFF", properties.getProperty("logging.level.org.hibernate.orm.jdbc.bind"));
        assertEquals("OFF", properties.getProperty("logging.level.org.springframework.jdbc.core"));
    }

    @Test
    void profileIsolationProcessorRejectsServingAndMigrationBeforeFlyway() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("cloudrun", "migration");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProfileIsolationEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, new SpringApplication()));

        assertEquals("Invalid production configuration: incompatible profiles", failure.getMessage());
    }

    @Test
    void profileIsolationProcessorIsRegisteredBySpringBootEnvironmentHook() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("META-INF/spring.factories"));

        assertEquals(ProfileIsolationEnvironmentPostProcessor.class.getName(),
                properties.getProperty("org.springframework.boot.env.EnvironmentPostProcessor"));
    }

    @Test
    void canonicalHttpsOriginIsAcceptedForProductionBaseUrl() {
        assertDoesNotThrow(() -> new ProductionConfigurationValidator(validEnvironment()).validate());
    }

    @Test
    void missingActiveProfileFailsFastWithoutSecretLeak() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles();
        environment.withProperty("SPRING_PROFILES_ACTIVE", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: SPRING_PROFILES_ACTIVE", failure.getMessage());
        assertFalse(failure.toString().contains(SECRET));
    }

    @Test
    void onlyNonProductionActiveProfileFailsFast() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("staging");
        environment.withProperty("SPRING_PROFILES_ACTIVE", "staging");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: SPRING_PROFILES_ACTIVE", failure.getMessage());
    }

    @Test
    void multipleActiveProfilesIncludingProductionAreAccepted() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("prod", "metrics");
        environment.withProperty("SPRING_PROFILES_ACTIVE", "prod,metrics");

        assertDoesNotThrow(() -> new ProductionConfigurationValidator(environment).validate());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "${APP_BASE_URL}",
            "${APP_BASE_URL:}",
            "prefix-${APP_BASE_URL}-suffix"
    })
    void unresolvedSpringPlaceholdersFailWithoutEchoingValue(String unresolvedValue) {
        MockEnvironment environment = validEnvironment().withProperty("APP_BASE_URL", unresolvedValue);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: APP_BASE_URL", failure.getMessage());
        assertFalse(failure.toString().contains(unresolvedValue));
        assertFalse(failure.toString().contains(SECRET));
    }

    @Test
    void unresolvedRequiredPlaceholderFailsWithoutEchoingValue() {
        MockEnvironment environment = validEnvironment().withProperty("MYSQL_DATABASE", "${MYSQL_DATABASE}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: MYSQL_DATABASE", failure.getMessage());
        assertFalse(failure.toString().contains("${MYSQL_DATABASE}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MYSQL_DATABASE", "MYSQL_USERNAME", "MYSQL_PASSWORD",
            "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "CLOUDINARY_CLOUD_NAME",
            "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET", "AGORA_APP_ID",
            "AGORA_APP_CERTIFICATE", "WEBSOCKET_ALLOWED_ORIGIN_PATTERNS",
            "SPRING_PROFILES_ACTIVE"
    })
    void everyRequiredNameRejectsItsUnresolvedPlaceholder(String name) {
        String unresolvedValue = "${" + name + "}";
        MockEnvironment environment = validEnvironment().withProperty(name, unresolvedValue);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertFalse(failure.toString().contains(unresolvedValue));
        assertFalse(failure.toString().contains(SECRET));
    }

    @Test
    void componentScanLoadsValidatorAndRejectsMissingProductionProfile() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles();
        environment.withProperty("SPRING_PROFILES_ACTIVE", "");

        Exception failure = assertThrows(Exception.class, () -> {
            try (AnnotationConfigApplicationContext ignored = scannedContextWith(environment)) {
                // Component scan must discover and instantiate the validator.
            }
        });

        assertFalse(failure.toString().contains(SECRET));
        assertFalse(failure.toString().contains("${"));
    }

    @Test
    void productionComponentScanRejectsLocalhostDataSource() {
        MockEnvironment environment = validEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/videocall_test")
                .withProperty("SPRING_PROFILES_ACTIVE", "prod");

        Exception failure = assertThrows(Exception.class, () -> {
            try (AnnotationConfigApplicationContext ignored = scannedContextWith(environment)) {
                // Production startup must reject a localhost/misbound datasource.
            }
        });

        assertFalse(failure.toString().contains("localhost"));
        assertFalse(failure.toString().contains(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "https://app.example.com"
    })
    void canonicalHttpsDomainOriginsAreAcceptedForProductionBaseUrl(String baseUrl) {
        MockEnvironment environment = validEnvironment().withProperty("APP_BASE_URL", baseUrl);

        assertDoesNotThrow(() -> new ProductionConfigurationValidator(environment).validate());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.test/",
            "https://example.test/path",
            "https://user:password@example.test",
            "https://example.test?query=value",
            "https://example.test#fragment",
            "http://example.test",
            "https://example.test:8443",
            "https://*",
            "https://localhost",
            "https://127.0.0.1",
            "https://[::1]",
            "not-an-origin"
    })
    void nonCanonicalBaseUrlFailsValidation(String baseUrl) {
        MockEnvironment environment = validEnvironment().withProperty("APP_BASE_URL", baseUrl);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: PUBLIC_BASE_URL", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*",
            "https://*",
            "https://*.example.test",
            "https://example.test:*",
            "http://[::1]:*",
            "https://[::1]",
            "https://[0:0:0:0:0:0:0:1]",
            "https://localhost",
            "https://subdomain.localhost",
            "https://127.0.0.1:8443",
            "https://127.44.12.9",
            "https://0.0.0.0",
            "https://example.test/path",
            "https://example.test?query=value",
            "https://user@example.test",
            "not-an-origin"
    })
    void invalidProductionWebSocketOriginFailsWithoutEchoingValues(String origin) {
        MockEnvironment environment = validEnvironment()
                .withProperty("WEBSOCKET_ALLOWED_ORIGIN_PATTERNS", origin);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).validate());

        assertEquals("Invalid production configuration: WEBSOCKET_ALLOWED_ORIGIN_PATTERNS", failure.getMessage());
    }

    @Test
    void exactHttpsWebSocketOriginMayUseDeterministicExplicitPort() {
        MockEnvironment environment = validEnvironment()
                .withProperty("WEBSOCKET_ALLOWED_ORIGIN_PATTERNS", "https://staging.example.test:8443");

        assertDoesNotThrow(() -> new ProductionConfigurationValidator(environment).validate());
    }

    private AnnotationConfigApplicationContext contextWith(MockEnvironment environment) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(ProductionConfigurationValidator.class);
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext scannedContextWith(MockEnvironment environment) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        var scanner = new org.springframework.context.annotation.ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ProductionConfigurationValidator.class));
        scanner.scan("com.example.videocall_marching_language.config");
        context.refresh();
        return context;
    }

    private MockEnvironment validEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment
                .withProperty("spring.profiles.active", "prod")
                .withProperty("MYSQL_DATABASE", "videocall_test")
                .withProperty("MYSQL_USERNAME", "dummy-user")
                .withProperty("MYSQL_PASSWORD", SECRET)
                .withProperty("GOOGLE_CLIENT_ID", "dummy-google-client")
                .withProperty("GOOGLE_CLIENT_SECRET", SECRET)
                .withProperty("CLOUDINARY_CLOUD_NAME", "dummy-cloud")
                .withProperty("CLOUDINARY_API_KEY", SECRET)
                .withProperty("CLOUDINARY_API_SECRET", SECRET)
                .withProperty("AGORA_APP_ID", "dummy-agora-app")
                .withProperty("AGORA_APP_CERTIFICATE", SECRET)
                .withProperty("WEBSOCKET_ALLOWED_ORIGIN_PATTERNS", "https://example.test")
                .withProperty("APP_BASE_URL", "https://example.test")
                .withProperty("SERVER_PORT", "8080")
                .withProperty("SPRING_PROFILES_ACTIVE", "prod")
                .withProperty("spring.datasource.url", "jdbc:mysql://mysql:3306/videocall_test");
    }

    private MockEnvironment cloudRunEnvironment() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("cloudrun");
        return environment
                .withProperty("spring.profiles.active", "cloudrun")
                .withProperty("SPRING_PROFILES_ACTIVE", "cloudrun")
                .withProperty("CLOUD_SQL_INSTANCE_CONNECTION_NAME", "project:region:instance")
                .withProperty("MYSQL_USERNAME", "app_runtime")
                .withProperty("spring.datasource.url", "jdbc:mysql:///${MYSQL_DATABASE}")
                .withProperty("spring.datasource.hikari.data-source-properties.socketFactory",
                        "com.google.cloud.sql.mysql.SocketFactory")
                .withProperty("spring.datasource.hikari.data-source-properties.ipTypes", "PUBLIC")
                .withProperty("spring.datasource.hikari.data-source-properties.cloudSqlRefreshStrategy", "lazy");
    }

    private MockEnvironment migrationEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("migration");
        return environment
                .withProperty("SPRING_PROFILES_ACTIVE", "migration")
                .withProperty("MYSQL_DATABASE", "video_call_staging")
                .withProperty("MIGRATION_MYSQL_USERNAME", "app_migrator")
                .withProperty("MIGRATION_MYSQL_PASSWORD", SECRET)
                .withProperty("CLOUD_SQL_INSTANCE_CONNECTION_NAME", "project:region:instance")
                .withProperty("spring.datasource.url", "jdbc:mysql:///${MYSQL_DATABASE}")
                .withProperty("spring.datasource.hikari.data-source-properties.socketFactory",
                        "com.google.cloud.sql.mysql.SocketFactory")
                .withProperty("spring.profiles.active", "migration")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.datasource.hikari.data-source-properties.ipTypes", "PUBLIC")
                .withProperty("spring.datasource.hikari.data-source-properties.cloudSqlRefreshStrategy", "lazy");
    }
}
