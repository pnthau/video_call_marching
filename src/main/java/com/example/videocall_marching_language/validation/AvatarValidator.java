package com.example.videocall_marching_language.validation;

import com.example.videocall_marching_language.exception.InvalidAvatarException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

@Component
public class AvatarValidator {

    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public void validate(MultipartFile avatar) {
        if (avatar == null || avatar.isEmpty()) {
            throw new InvalidAvatarException("AVATAR_EMPTY", "Vui lòng chọn ảnh avatar");
        }
        if (avatar.getSize() > MAX_AVATAR_SIZE) {
            throw new InvalidAvatarException("AVATAR_TOO_LARGE", "Avatar không được vượt quá 5 MiB");
        }
        String contentType = avatar.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAvatarException("AVATAR_TYPE_NOT_ALLOWED", "Avatar chỉ hỗ trợ JPEG hoặc PNG");
        }

        String detectedContentType = detectContentType(avatar);
        if (detectedContentType == null) {
            throw new InvalidAvatarException("AVATAR_INVALID_CONTENT", "Nội dung avatar không hợp lệ");
        }
        if (!contentType.equals(detectedContentType)) {
            throw new InvalidAvatarException("AVATAR_TYPE_MISMATCH", "Loại nội dung avatar không khớp");
        }

        validateReadableImage(avatar, detectedContentType);
    }

    private String detectContentType(MultipartFile avatar) {
        try (InputStream inputStream = avatar.getInputStream()) {
            byte[] leadingBytes = inputStream.readNBytes(PNG_SIGNATURE.length);
            if (hasSignature(leadingBytes, JPEG_SIGNATURE)) {
                return "image/jpeg";
            }
            if (hasSignature(leadingBytes, PNG_SIGNATURE)) {
                return "image/png";
            }
            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean hasSignature(byte[] source, byte[] signature) {
        if (source.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (source[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private void validateReadableImage(MultipartFile avatar, String detectedContentType) {
        ImageReader reader = null;
        try (InputStream inputStream = avatar.getInputStream();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            if (imageInputStream == null) {
                throw invalidContent();
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                throw invalidContent();
            }

            reader = readers.next();
            if (!readerFormatMatches(reader, detectedContentType)) {
                throw invalidContent();
            }

            reader.setInput(imageInputStream, false, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw invalidContent();
            }

            long pixels = calculatePixels(width, height);
            if (width > 4096 || height > 4096 || pixels > 16_777_216L) {
                throw new InvalidAvatarException(
                        "AVATAR_DIMENSIONS_EXCEEDED",
                        "Kích thước avatar vượt quá giới hạn"
                );
            }

            if (reader.read(0) == null) {
                throw invalidContent();
            }
        } catch (InvalidAvatarException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidContent();
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    static long calculatePixels(int width, int height) {
        return (long) width * (long) height;
    }

    private boolean readerFormatMatches(ImageReader reader, String detectedContentType) throws IOException {
        String readerFormat = reader.getFormatName();
        if ("image/jpeg".equals(detectedContentType)) {
            return "JPEG".equalsIgnoreCase(readerFormat) || "JPG".equalsIgnoreCase(readerFormat);
        }
        return "image/png".equals(detectedContentType) && "PNG".equalsIgnoreCase(readerFormat);
    }

    private InvalidAvatarException invalidContent() {
        return new InvalidAvatarException("AVATAR_INVALID_CONTENT", "Nội dung avatar không hợp lệ");
    }
}
