-- Unified Migration V5 to V11 (Squashed for main branch merge)
-- Includes: Practice Scripts, AI Settings, Audio Lessons, and Relationships

-- 1. Thêm cột lưu trạng thái Provider nào đang được kích hoạt vào bảng users (từ V6)
SET @col_exists = (
    SELECT COUNT(*) 
    FROM INFORMATION_SCHEMA.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'users' 
      AND COLUMN_NAME = 'active_ai_provider'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE `users` ADD COLUMN `active_ai_provider` VARCHAR(20) DEFAULT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Tạo bảng lưu trữ API Key theo từng Provider của User (từ V6)
CREATE TABLE IF NOT EXISTS `user_ai_settings` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `ai_provider` VARCHAR(20) NOT NULL,
    `ai_api_key` TEXT NOT NULL,
    CONSTRAINT fk_user_ai_settings_user FOREIGN KEY (`user_id`) REFERENCES `users`(`id`),
    CONSTRAINT uk_user_provider UNIQUE (`user_id`, `ai_provider`)
);

-- 3. Bảng Scripts (từ V5, cập nhật thêm cột level từ V10)
CREATE TABLE IF NOT EXISTS `scripts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tag_id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `content` TEXT NOT NULL,
    `language` VARCHAR(255) NOT NULL,
    `level` VARCHAR(20) NOT NULL DEFAULT 'N5',
    `phonetic` TEXT,
    `meaning_content` TEXT,
    `target_duration` INT,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`tag_id`) REFERENCES `tags`(`id`)
);

-- Thêm composite index tối ưu hóa cho truy vấn nhóm và tìm kiếm theo (language, level)
SET @idx_exists = (
    SELECT COUNT(*) 
    FROM INFORMATION_SCHEMA.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'scripts' 
      AND INDEX_NAME = 'idx_scripts_language_level'
);
SET @sql_idx = IF(@idx_exists = 0,
    'ALTER TABLE `scripts` ADD INDEX `idx_scripts_language_level` (`language`, `level`)',
    'SELECT 1'
);
PREPARE stmt_idx FROM @sql_idx; EXECUTE stmt_idx; DEALLOCATE PREPARE stmt_idx;

-- 4. Bảng Practice Histories (từ V5)
CREATE TABLE IF NOT EXISTS `practice_histories` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `script_id` BIGINT NOT NULL,
    `phase` INT NOT NULL,
    `start_time` DATETIME NOT NULL,
    `end_time` DATETIME,
    `duration_in_seconds` INT,
    `is_passed` BOOLEAN,
    `ai_feedback` TEXT,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`),
    FOREIGN KEY (`script_id`) REFERENCES `scripts`(`id`)
);

-- 5. Bảng Audio Lessons (từ V7, cập nhật script_id từ V9, level từ V11)
CREATE TABLE IF NOT EXISTS `audio_lessons` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tag_id` BIGINT NOT NULL,
    `user_id` BIGINT NULL,
    `script_id` BIGINT NULL,
    `title` VARCHAR(255) NOT NULL,
    `audio_url` VARCHAR(500) NOT NULL,
    `duration_in_seconds` INT NULL,
    `language` VARCHAR(20) NOT NULL DEFAULT 'ja',
    `level` VARCHAR(20) NOT NULL DEFAULT 'N5',
    `status` VARCHAR(20) NOT NULL DEFAULT 'READY',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_audio_lessons_tag` FOREIGN KEY (`tag_id`) REFERENCES `tags`(`id`),
    CONSTRAINT `fk_audio_lessons_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`),
    CONSTRAINT `fk_audio_lessons_script` FOREIGN KEY (`script_id`) REFERENCES `scripts`(`id`) ON DELETE SET NULL
);

-- Đánh chỉ mục kép tăng tốc truy vấn lọc cho audio_lessons
SET @idx_exists_audio = (
    SELECT COUNT(*) 
    FROM INFORMATION_SCHEMA.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() 
      AND TABLE_NAME = 'audio_lessons' 
      AND INDEX_NAME = 'idx_audio_lessons_language_level'
);
SET @sql_idx_audio = IF(@idx_exists_audio = 0,
    'ALTER TABLE `audio_lessons` ADD INDEX `idx_audio_lessons_language_level` (`language`, `level`)',
    'SELECT 1'
);
PREPARE stmt_idx_audio FROM @sql_idx_audio; EXECUTE stmt_idx_audio; DEALLOCATE PREPARE stmt_idx_audio;

-- 6. Bảng Lesson Sentences (từ V7)
CREATE TABLE IF NOT EXISTS `lesson_sentences` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `lesson_id` BIGINT NOT NULL,
    `sentence_index` INT NOT NULL,
    `start_time` DOUBLE NOT NULL,
    `end_time` DOUBLE NOT NULL,
    `original_text` TEXT NOT NULL,
    `phonetic` TEXT NULL,
    `vietnamese_meaning` TEXT NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_lesson_sentences_lesson` FOREIGN KEY (`lesson_id`) REFERENCES `audio_lessons`(`id`) ON DELETE CASCADE
);

-- 7. Bảng Sentence Grammars (từ V7)
CREATE TABLE IF NOT EXISTS `sentence_grammars` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `sentence_id` BIGINT NOT NULL,
    `grammar_point` VARCHAR(255) NOT NULL,
    `explanation` TEXT NOT NULL,
    `formula` VARCHAR(255) NULL,
    `key_words_json` TEXT NULL,
    `sentence_challenge_prompt` TEXT NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_sentence_grammars_sentence` FOREIGN KEY (`sentence_id`) REFERENCES `lesson_sentences`(`id`) ON DELETE CASCADE
);

-- 8. Bảng Sentence Exercises (từ V7)
CREATE TABLE IF NOT EXISTS `sentence_exercises` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `sentence_id` BIGINT NOT NULL,
    `exercise_type` VARCHAR(30) NOT NULL,
    `question` TEXT NOT NULL,
    `options_json` TEXT NOT NULL,
    `correct_answer` VARCHAR(255) NOT NULL,
    `explanation` TEXT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_sentence_exercises_sentence` FOREIGN KEY (`sentence_id`) REFERENCES `lesson_sentences`(`id`) ON DELETE CASCADE
);

-- =========================================================================
-- DATA MIGRATIONS / UPDATES
-- =========================================================================

-- Fix challenge keywords (từ V8)
UPDATE `sentence_grammars`
SET `key_words_json` = '[{"kanji":"日曜日","hiragana":"にちようび","romaji":"nichiyoubi","meaning":"Chủ Nhật"},{"kanji":"コンサート","hiragana":"こんさーと","romaji":"konsaato","meaning":"buổi hòa nhạc"},{"kanji":"あります","hiragana":"あります","romaji":"arimasu","meaning":"có / diễn ra"}]'
WHERE `sentence_challenge_prompt` LIKE '%Chủ Nhật%' 
  AND (`sentence_challenge_prompt` LIKE '%hòa nhạc%' OR `sentence_challenge_prompt` LIKE '%buổi hòa nhạc%');

-- Gán trình độ chuẩn xác cho các bài học mẫu Scripts (từ V10)
UPDATE `scripts` SET `level` = 'N5' WHERE `language` = 'ja' AND (`title` LIKE '%cơ bản%' OR `title` LIKE '%Combini%' OR `title` LIKE '%tiện lợi%');
UPDATE `scripts` SET `level` = 'N4' WHERE `language` = 'ja' AND (`title` LIKE '%quán ăn%' OR `title` LIKE '%ga tàu%' OR `title` LIKE '%Hỏi đường%');
UPDATE `scripts` SET `level` = 'A1' WHERE `language` = 'en';

-- Gán trình độ theo script liên kết cho Audio Lessons nếu đã có (từ V11)
UPDATE `audio_lessons` al
JOIN `scripts` s ON al.script_id = s.id
SET al.level = s.level
WHERE al.script_id IS NOT NULL;
