package com.example.videocall_marching_language.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.List;
import java.util.Set;

@Configuration
public class ProductionConfigurationValidator {

    private static final List<String> REQUIRED_ENVIRONMENT_NAMES = List.of(
            "MYSQL_DATABASE",
            "MYSQL_USERNAME",
            "MYSQL_PASSWORD",
            "GOOGLE_CLIENT_ID",
            "GOOGLE_CLIENT_SECRET",
            "CLOUDINARY_CLOUD_NAME",
            "CLOUDINARY_API_KEY",
            "CLOUDINARY_API_SECRET",
            "AGORA_APP_ID",
            "AGORA_APP_CERTIFICATE",
            "WEBSOCKET_ALLOWED_ORIGIN_PATTERNS",
            "SPRING_PROFILES_ACTIVE"
    );

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validateAtStartup() {
        if (usesDefaultLocalDataSourceInDevelopment()) {
            return;
        }
        validate();
    }

    private boolean usesDefaultLocalDataSourceInDevelopment() {
        String datasourceUrl = readProperty("spring.datasource.url");
        return datasourceUrl != null
                && datasourceUrl.startsWith("jdbc:mysql://localhost:")
                && environment.getActiveProfiles().length == 0
                && isBlankProperty("SPRING_PROFILES_ACTIVE")
                && isBlankProperty("spring.profiles.active");
    }

    private boolean isBlankProperty(String name) {
        String value = readProperty(name);
        return value == null || value.isBlank();
    }

    private boolean isPropertyDefined(String name) {
        try {
            return environment.getProperty(name) != null;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    void validate() {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        validateProfileIsolation(profiles);
        if (profiles.contains("migration")) {
            validateMigrationConfiguration();
            return;
        }
        validateProductionProfile(profiles);

        for (String name : REQUIRED_ENVIRONMENT_NAMES) {
            String value = readProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required production configuration: " + name);
            }
            if (containsUnresolvedPlaceholder(value)) {
                throw new IllegalStateException("Invalid production configuration: " + name);
            }
        }

        validatePublicBaseUrl();
        validateServerPort();
        validateWebSocketOrigins();
        if (profiles.contains("cloudrun")) {
            validateCloudRunConnectorConfiguration();
        } else {
            validateProductionDataSource();
        }
    }

    private boolean containsUnresolvedPlaceholder(String value) {
        return value.matches(".*\\$\\{[^}]*}.*");
    }

    private void validateProductionProfile(Set<String> profiles) {
        String configuredProfiles = readProperty("SPRING_PROFILES_ACTIVE");
        if (configuredProfiles == null || configuredProfiles.isBlank()
                || profiles.stream().noneMatch(profile -> profile.equals("prod") || profile.equals("cloudrun"))) {
            throw new IllegalStateException("Invalid production configuration: SPRING_PROFILES_ACTIVE");
        }
    }

    private void validateProfileIsolation(Set<String> profiles) {
        boolean serving = profiles.contains("prod") || profiles.contains("cloudrun");
        if (profiles.contains("migration") && serving) {
            throw new IllegalStateException("Invalid production configuration: incompatible profiles");
        }
        if (profiles.contains("prod") && profiles.contains("cloudrun")) {
            throw new IllegalStateException("Invalid production configuration: incompatible profiles");
        }
    }

    private void validateMigrationConfiguration() {
        requireConfiguration("SPRING_PROFILES_ACTIVE");
        requireConfiguration("MYSQL_DATABASE");
        requireConfiguration("MIGRATION_MYSQL_USERNAME");
        requireConfiguration("MIGRATION_MYSQL_PASSWORD");
        requireConfiguration("CLOUD_SQL_INSTANCE_CONNECTION_NAME");
        if (!"app_migrator".equals(readProperty("MIGRATION_MYSQL_USERNAME"))) {
            throw new IllegalStateException("Invalid migration configuration: database user");
        }
        String url = readProperty("spring.datasource.url");
        if (!isConnectorDataSourceUrl(url)) {
            throw new IllegalStateException("Invalid migration configuration: datasource path");
        }
        if (!"com.google.cloud.sql.mysql.SocketFactory".equals(
                readProperty("spring.datasource.hikari.data-source-properties.socketFactory"))) {
            throw new IllegalStateException("Invalid migration configuration: connector path");
        }
        if (!"PUBLIC".equals(readProperty("spring.datasource.hikari.data-source-properties.ipTypes"))
                || !"lazy".equals(readProperty(
                "spring.datasource.hikari.data-source-properties.cloudSqlRefreshStrategy"))) {
            throw new IllegalStateException("Invalid migration configuration: connector policy");
        }
    }

    private void requireConfiguration(String name) {
        String value = readProperty(name);
        if (value == null || value.isBlank() || containsUnresolvedPlaceholder(value)) {
            throw new IllegalStateException("Missing required production configuration: " + name);
        }
    }

    private String readProperty(String name) {
        try {
            return environment.getProperty(name);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid production configuration: " + name);
        }
    }

    private void validatePublicBaseUrl() {
        String baseUrl = readProperty("PUBLIC_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = readProperty("APP_BASE_URL");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Invalid production configuration: PUBLIC_BASE_URL");
        }
        try {
            URI uri = URI.create(baseUrl);
            // APP_BASE_URL is the canonical public origin: HTTPS default port only.
            if (!isExactHttpsOrigin(uri)
                    || uri.getPort() != -1
                    || isLoopbackOrUnspecifiedHost(uri.getHost())) {
                throw new IllegalStateException("Invalid production configuration: PUBLIC_BASE_URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid production configuration: PUBLIC_BASE_URL");
        }
    }

    private void validateServerPort() {
        String serverPort;
        String portName;
        if (environment.containsProperty("PORT")) {
            serverPort = readProperty("PORT");
            portName = "PORT";
            if (serverPort == null || serverPort.isBlank()) {
                throw invalidPort(portName);
            }
        } else {
            serverPort = readProperty("SERVER_PORT");
            portName = "SERVER_PORT";
            if (serverPort == null || serverPort.isBlank()) {
                throw invalidPort(portName);
            }
        }
        try {
            int port = Integer.parseInt(serverPort);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw invalidPort(portName);
        }
    }

    private IllegalStateException invalidPort(String portName) {
        return new IllegalStateException("Invalid production configuration: " + portName);
    }

    private void validateWebSocketOrigins() {
        String origins = readProperty("WEBSOCKET_ALLOWED_ORIGIN_PATTERNS");
        for (String origin : origins.split(",")) {
            String candidate = origin.trim();
            try {
                URI uri = URI.create(candidate);
                // Production accepts exact HTTPS origins only; wildcard hosts/ports are forbidden.
                if (!isExactHttpsOrigin(uri) || candidate.contains("*")) {
                    throw new IllegalArgumentException();
                }
                String host = uri.getHost().toLowerCase();
                if (isLoopbackOrUnspecifiedHost(host)) {
                    throw new IllegalArgumentException();
                }
                if (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid production configuration: WEBSOCKET_ALLOWED_ORIGIN_PATTERNS");
            }
        }
    }

    private void validateProductionDataSource() {
        String datasourceUrl = readProperty("spring.datasource.url");
        if (datasourceUrl == null) {
            return;
        }
        String mysqlPrefix = "jdbc:mysql://";
        if (!datasourceUrl.startsWith(mysqlPrefix)) {
            throw new IllegalStateException("Invalid production configuration: spring.datasource.url");
        }
        try {
            URI uri = URI.create("mysql://" + datasourceUrl.substring(mysqlPrefix.length()));
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getUserInfo() != null || isForbiddenDataSourceHost(host)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid production configuration: spring.datasource.url");
        }
    }

    private void validateCloudRunConnectorConfiguration() {
        if (!isConnectorDataSourceUrl(readProperty("spring.datasource.url"))) {
            throw new IllegalStateException("Invalid Cloud Run configuration: connector datasource");
        }
        requireConfiguration("CLOUD_SQL_INSTANCE_CONNECTION_NAME");
        if (!"app_runtime".equals(readProperty("MYSQL_USERNAME"))) {
            throw new IllegalStateException("Invalid Cloud Run configuration: database user");
        }
        if (!"com.google.cloud.sql.mysql.SocketFactory".equals(
                readProperty("spring.datasource.hikari.data-source-properties.socketFactory"))) {
            throw new IllegalStateException("Invalid Cloud Run configuration: connector path");
        }
        if (!"PUBLIC".equals(readProperty("spring.datasource.hikari.data-source-properties.ipTypes"))) {
            throw new IllegalStateException("Invalid Cloud Run configuration: connector IP type");
        }
    }

    private boolean isConnectorDataSourceUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql:///")) {
            return false;
        }
        String databasePart = url.substring("jdbc:mysql:///".length());
        return !databasePart.isBlank() && !databasePart.contains("?")
                && !databasePart.contains("#") && !databasePart.contains("/");
    }

    private boolean isForbiddenDataSourceHost(String host) {
        String normalized = host.toLowerCase();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.equals("::1")) {
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        try {
            return Integer.parseInt(octets[0]) == 127
                    && Integer.parseInt(octets[1]) >= 0 && Integer.parseInt(octets[1]) <= 255
                    && Integer.parseInt(octets[2]) >= 0 && Integer.parseInt(octets[2]) <= 255
                    && Integer.parseInt(octets[3]) >= 0 && Integer.parseInt(octets[3]) <= 255;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isExactHttpsOrigin(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getRawAuthority() != null
                && !uri.getRawAuthority().contains("*");
    }

    private boolean isLoopbackOrUnspecifiedHost(String host) {
        String normalized = host.toLowerCase();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")
                || normalized.startsWith("127.");
    }
}
