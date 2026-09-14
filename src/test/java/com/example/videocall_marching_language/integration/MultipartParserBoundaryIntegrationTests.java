package com.example.videocall_marching_language.integration;

import com.cloudinary.Cloudinary;
import com.example.videocall_marching_language.service.AvatarStorageService;
import com.example.videocall_marching_language.service.IUserService;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultipartParserBoundaryIntegrationTests {

    private static final int FIVE_MIB = 5 * 1024 * 1024;
    private static final int SIX_MIB = 6 * 1024 * 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private AvatarStorageService avatarStorageService;

    @Autowired
    private Cloudinary cloudinary;

    @Autowired
    private IUserService userService;

    private int lastRequestContentLength;

    @Test
    void fileOneByteOverFiveMiBIsRejectedByFileLimitBeforeProfileMutation() throws Exception {
        int filePartSize = FIVE_MIB + 1;
        HttpURLConnection connection = postMultipart("/profile/edit", filePartSize);
        int requestContentLength = lastRequestContentLength;
        int status = connection.getResponseCode();
        String body = readBody(connection);

        assertTrue(requestContentLength > 0, "client must provide Content-Length");
        assertTrue(requestContentLength < SIX_MIB,
                "multipart request must stay below the 6 MiB request limit: " + requestContentLength);
        assertEquals(413, status);
        assertTrue(connection.getContentType().startsWith("text/html"));
        assertFalse(body.contains("Whitelabel"));
        assertFalse(body.contains("/profile/edit"));
        assertFalse(body.contains("avatar-private.png"));
        assertFalse(body.contains("MaxUploadSizeExceededException"));
        verifyNoInteractions(userService, avatarStorageService, cloudinary);
    }

    @Test
    void exactFiveMiBMultipartCrossesTheServletParserAndReachesDownstreamSecurityFlow() throws Exception {
        HttpURLConnection connection = postMultipart(FIVE_MIB);

        assertEquals(403, connection.getResponseCode());
        assertTrue(connection.getContentType().startsWith("application/json"));
        assertTrue(readBody(connection).contains("ACCESS_DENIED"));
    }

    @Test
    void boundedTomcatSwallowConfigurationIsBound() {
        assertEquals("8MB", environment.getProperty("server.tomcat.max-swallow-size"));
    }

    @Test
    void requestAboveSixMiBWithinEightMiBSwallowReturnsSanitizedApi413WithoutConnectionReset() throws Exception {
        HttpURLConnection connection = postMultipart(SIX_MIB + 1);

        assertEquals(413, connection.getResponseCode());
        assertTrue(connection.getContentType().startsWith("application/json"));
        String body = readBody(connection);
        assertTrue(body.contains("PAYLOAD_TOO_LARGE"), body);
        assertFalse(body.contains("Whitelabel"));
        assertFalse(body.contains("avatar-private.png"));
    }

    @Test
    void requestBeyondEightMiBMayBeClosedButNeverProducesSensitiveBody() throws Exception {
        HttpURLConnection connection = postMultipart(8 * 1024 * 1024 + 1);
        try {
            int status = connection.getResponseCode();
            String body = readBody(connection);
            assertFalse(body.contains("avatar-private.png"));
            assertTrue(status == 413 || status >= 400);
        } catch (java.net.SocketException expectedConnectorClose) {
            assertTrue(expectedConnectorClose.getMessage().toLowerCase().contains("connection")
                    || expectedConnectorClose.getMessage().toLowerCase().contains("reset"));
        }
    }

    private HttpURLConnection postMultipart(int fileSize) throws Exception {
        return postMultipart("/api/parser-boundary", fileSize);
    }

    private HttpURLConnection postMultipart(String path, int fileSize) throws Exception {
        String boundary = "Slice4Boundary";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar-private.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + fileSize + suffix.length];
        lastRequestContentLength = body.length;
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(suffix, 0, body, prefix.length + fileSize, suffix.length);

        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + path
        ).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.getOutputStream().write(body);
        return connection;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BoundaryTestConfiguration {
        @Bean("boundaryAvatarStorageService")
        @Primary
        AvatarStorageService avatarStorageService() {
            return Mockito.mock(AvatarStorageService.class);
        }

        @Bean("boundaryCloudinary")
        @Primary
        Cloudinary cloudinary() {
            return Mockito.mock(Cloudinary.class);
        }

        @Bean("boundaryUserService")
        @Primary
        IUserService userService() {
            return Mockito.mock(IUserService.class);
        }
    }

    private String readBody(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getErrorStream() != null
                ? connection.getErrorStream() : connection.getInputStream();
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            stream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
