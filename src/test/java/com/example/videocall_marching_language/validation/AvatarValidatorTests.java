package com.example.videocall_marching_language.validation;

import com.example.videocall_marching_language.exception.InvalidAvatarException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AvatarValidatorTests {

    private static final int MAX_AVATAR_SIZE = 5 * 1024 * 1024;
    private final AvatarValidator avatarValidator = new AvatarValidator();

    @Test
    void validate_whenFileIsNull_throwsEmptyCode() {
        assertErrorCode("AVATAR_EMPTY", null);
    }

    @Test
    void validate_whenFileIsEmpty_throwsEmptyCode() {
        assertErrorCode("AVATAR_EMPTY", file("image/png", new byte[0]));
    }

    @Test
    void validate_whenFileIsExactlyFiveMiB_doesNotRejectForSize() {
        assertDoesNotThrow(() -> avatarValidator.validate(validPngAtExactLimit()));
    }

    @Test
    void validate_whenFileIsLargerThanFiveMiB_throwsTooLargeCode() {
        assertErrorCode("AVATAR_TOO_LARGE", file("image/png", new byte[MAX_AVATAR_SIZE + 1]));
    }

    @Test
    void validate_whenDeclaredMimeIsOctetStream_throwsTypeNotAllowedCode() {
        assertErrorCode("AVATAR_TYPE_NOT_ALLOWED", file("application/octet-stream", new byte[]{1}));
    }

    @Test
    void validate_whenDeclaredMimeIsNull_throwsTypeNotAllowedCode() {
        assertErrorCode("AVATAR_TYPE_NOT_ALLOWED", file(null, new byte[]{1}));
    }

    @Test
    void validate_whenDeclaredMimeIsSvg_throwsTypeNotAllowedCode() {
        assertErrorCode("AVATAR_TYPE_NOT_ALLOWED", file("image/svg+xml", new byte[]{1}));
    }

    @Test
    void validate_whenDeclaredMimeIsGif_throwsTypeNotAllowedCode() {
        assertErrorCode("AVATAR_TYPE_NOT_ALLOWED", file("image/gif", new byte[]{1}));
    }

    @Test
    void validate_whenDeclaredMimeIsWebp_throwsTypeNotAllowedCode() {
        assertErrorCode("AVATAR_TYPE_NOT_ALLOWED", file("image/webp", new byte[]{1}));
    }

    @Test
    void validate_whenJpegBytesMatchJpegMime_doesNotThrow() {
        assertDoesNotThrow(() -> avatarValidator.validate(file("image/jpeg", createValidJpeg())));
    }

    @Test
    void validate_whenPngBytesMatchPngMime_doesNotThrow() {
        assertDoesNotThrow(() -> avatarValidator.validate(file("image/png", createValidPng())));
    }

    @Test
    void validate_whenJpegBytesUsePngMime_throwsTypeMismatchCode() {
        assertErrorCode("AVATAR_TYPE_MISMATCH", file("image/png", createValidJpeg()));
    }

    @Test
    void validate_whenPngBytesUseJpegMime_throwsTypeMismatchCode() {
        assertErrorCode("AVATAR_TYPE_MISMATCH", file("image/jpeg", createValidPng()));
    }

    @Test
    void validate_whenRandomBytesUseJpegMime_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file("image/jpeg", new byte[]{0x01, 0x23, 0x45}));
    }

    @Test
    void validate_whenRandomBytesUsePngMime_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file("image/png", new byte[]{0x01, 0x23, 0x45}));
    }

    @Test
    void validate_whenJpegContainsOnlyMagicBytes_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file("image/jpeg", new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF
        }));
    }

    @Test
    void validate_whenPngContainsOnlySignatureBytes_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file("image/png", new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        }));
    }

    @Test
    void validate_whenPngHasRecognizedHeaderButTruncatedBody_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file("image/png", truncatedPngWithValidHeader()));
    }

    @Test
    void validate_whenWidthIs4096AndHeightIsSmall_doesNotThrow() {
        assertDoesNotThrow(() -> avatarValidator.validate(file("image/png", createPng(4096, 1))));
    }

    @Test
    void validate_whenHeightIs4096AndWidthIsSmall_doesNotThrow() {
        assertDoesNotThrow(() -> avatarValidator.validate(file("image/png", createPng(1, 4096))));
    }

    @Test
    void validate_whenWidthExceeds4096_throwsDimensionsExceededCode() {
        assertErrorCode("AVATAR_DIMENSIONS_EXCEEDED", file(
                "image/png", rewritePngDimensions(createValidPng(), 4097, 1)
        ));
    }

    @Test
    void validate_whenHeightExceeds4096_throwsDimensionsExceededCode() {
        assertErrorCode("AVATAR_DIMENSIONS_EXCEEDED", file(
                "image/png", rewritePngDimensions(createValidPng(), 1, 4097)
        ));
    }

    @Test
    void validate_whenPixelCountExceedsLimit_throwsDimensionsExceededCode() {
        assertErrorCode("AVATAR_DIMENSIONS_EXCEEDED", file(
                "image/png", rewritePngDimensions(createValidPng(), 4096, 4097)
        ));
    }

    @Test
    void validate_whenWidthIsNonPositive_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file(
                "image/png", rewritePngDimensions(createValidPng(), 0, 1)
        ));
    }

    @Test
    void validate_whenHeightIsNonPositive_throwsInvalidContentCode() {
        assertErrorCode("AVATAR_INVALID_CONTENT", file(
                "image/png", rewritePngDimensions(createValidPng(), 1, 0)
        ));
    }

    @Test
    void calculatePixels_usesLongArithmetic() {
        assertEquals(4_294_967_296L, AvatarValidator.calculatePixels(65_536, 65_536));
    }

    private void assertErrorCode(String expectedCode, MultipartFile avatar) {
        InvalidAvatarException exception = assertThrows(
                InvalidAvatarException.class,
                () -> avatarValidator.validate(avatar)
        );

        assertEquals(expectedCode, exception.getErrorCode());
    }

    private MockMultipartFile file(String contentType, byte[] content) {
        return new MockMultipartFile("avatar", "avatar.png", contentType, content);
    }

    private MockMultipartFile validPngAtExactLimit() {
        return file("image/png", pngWithAncillaryPadding(MAX_AVATAR_SIZE));
    }

    private byte[] pngWithAncillaryPadding(int targetSize) {
        byte[] basePng = createBasePng();
        int ihdrEndOffset = 8 + 25;
        int paddingLength = targetSize - basePng.length - 12;
        if (paddingLength < 0) {
            throw new IllegalArgumentException("Kích thước PNG mục tiêu không hợp lệ");
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(targetSize);
            output.write(basePng, 0, ihdrEndOffset);
            writeAncillaryChunk(output, paddingLength);
            output.write(basePng, ihdrEndOffset, basePng.length - ihdrEndOffset);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo PNG fixture", exception);
        }
    }

    private byte[] createBasePng() {
        return createImage("png", BufferedImage.TYPE_INT_ARGB);
    }

    private byte[] createValidPng() {
        return createPng(1, 1);
    }

    private byte[] createValidJpeg() {
        return createImage("jpeg", BufferedImage.TYPE_INT_RGB);
    }

    private byte[] createPng(int width, int height) {
        return createImage("png", BufferedImage.TYPE_INT_ARGB, width, height);
    }

    private byte[] createImage(String format, int imageType) {
        return createImage(format, imageType, 1, 1);
    }

    private byte[] createImage(String format, int imageType, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, imageType);
        image.setRGB(0, 0, 0xFF336699);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("Không thể tạo image fixture");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo image fixture", exception);
        }
    }

    private void writeAncillaryChunk(ByteArrayOutputStream output, int dataLength) throws IOException {
        byte[] type = new byte[]{'v', 'p', 'A', 'g'};
        byte[] data = new byte[dataLength];

        DataOutputStream dataOutput = new DataOutputStream(output);
        dataOutput.writeInt(data.length);
        dataOutput.write(type);
        dataOutput.write(data);

        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        dataOutput.writeInt((int) crc.getValue());
    }

    private byte[] truncatedPngWithValidHeader() {
        return Arrays.copyOf(createValidPng(), 33);
    }

    private byte[] rewritePngDimensions(byte[] png, int width, int height) {
        byte[] rewritten = Arrays.copyOf(png, png.length);
        writeInt(rewritten, 16, width);
        writeInt(rewritten, 20, height);

        CRC32 crc = new CRC32();
        crc.update(rewritten, 12, 17);
        writeInt(rewritten, 29, (int) crc.getValue());
        return rewritten;
    }

    private void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
