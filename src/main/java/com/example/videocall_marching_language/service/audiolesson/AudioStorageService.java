package com.example.videocall_marching_language.service.audiolesson;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioStorageService {

    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024; // 15 MB
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".ogg", ".webm", ".aac"
    );

    private final Cloudinary cloudinary;

    public AudioUploadResult uploadAudio(MultipartFile file) {
        validateAudioFile(file);

        // 1. Thử tải lên Cloudinary với định dạng chuẩn MP3
        try {
            log.info("Bắt đầu tải audio lên Cloudinary: name={}, size={} bytes", file.getOriginalFilename(), file.getSize());
            Map<?, ?> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "videocall-marching/audio-lessons",
                            "resource_type", "video", // Cloudinary dùng 'video' cho file âm thanh
                            "format", "mp3"
                    )
            );

            Object secureUrl = result.get("secure_url");
            Object publicId = result.get("public_id");
            Object durationObj = result.get("duration");

            Integer duration = null;
            if (durationObj instanceof Number) {
                duration = ((Number) durationObj).intValue();
            }

            if (secureUrl != null) {
                String audioUrl = secureUrl.toString();
                // Đảm bảo URL kết thúc với đuôi .mp3
                if (!audioUrl.toLowerCase().endsWith(".mp3") && !audioUrl.toLowerCase().endsWith(".wav")) {
                    audioUrl += ".mp3";
                }
                log.info("Tải audio lên Cloudinary thành công: url={}, duration={}", audioUrl, duration);
                return new AudioUploadResult(audioUrl, publicId != null ? publicId.toString() : "", duration);
            }

        } catch (Exception e) {
            log.warn("Không thể tải lên Cloudinary ({}), chuyển sang lưu trữ cục bộ: {}", e.getMessage(), e.getClass().getSimpleName());
        }

        // 2. Fallback: Lưu trữ cục bộ trong uploads/audio
        return saveLocally(file);
    }

    private void validateAudioFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn file âm thanh.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Dung lượng file âm thanh không được vượt quá 15 MB.");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            throw new IllegalArgumentException("Tên file không hợp lệ. Vui lòng chọn file có định dạng âm thanh (.mp3, .wav, .m4a...).");
        }

        String extension = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Định dạng file " + extension + " không được hỗ trợ. Chỉ hỗ trợ MP3, WAV, M4A, OGG, WEBM.");
        }
    }

    private AudioUploadResult saveLocally(MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.mp3";
            String extension = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".")) : ".mp3";
            String newFilename = UUID.randomUUID() + extension;

            File dir = new File("uploads/audio");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File targetFile = new File(dir, newFilename);
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                fos.write(file.getBytes());
            }

            String localUrl = "/uploads/audio/" + newFilename;
            log.info("Lưu file âm thanh cục bộ thành công: url={}", localUrl);
            return new AudioUploadResult(localUrl, newFilename, null);

        } catch (IOException e) {
            log.error("Lỗi khi lưu audio cục bộ: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể lưu trữ file âm thanh: " + e.getMessage(), e);
        }
    }

    public record AudioUploadResult(String secureUrl, String publicId, Integer durationInSeconds) {}
}
