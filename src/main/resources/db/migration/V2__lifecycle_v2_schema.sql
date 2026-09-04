-- V2: Lifecycle V2 schema changes
-- Adds SessionPresence table, new columns to learning_sessions, and updates enums
-- Idempotent: checks for existing columns/tables before creating

-- Add new columns to learning_sessions (idempotent)
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions' AND COLUMN_NAME = 'accumulated_overlap_seconds') = 0,
    'ALTER TABLE `learning_sessions` ADD COLUMN `accumulated_overlap_seconds` INT NOT NULL DEFAULT 0',
    'SELECT 1'
));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions' AND COLUMN_NAME = 'reconnect_deadline') = 0,
    'ALTER TABLE `learning_sessions` ADD COLUMN `reconnect_deadline` DATETIME',
    'SELECT 1'
));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions' AND COLUMN_NAME = 'user1_uid') = 0,
    'ALTER TABLE `learning_sessions` ADD COLUMN `user1_uid` INT',
    'SELECT 1'
));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_sessions' AND COLUMN_NAME = 'user2_uid') = 0,
    'ALTER TABLE `learning_sessions` ADD COLUMN `user2_uid` INT',
    'SELECT 1'
));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Create session_presence table (idempotent)
CREATE TABLE IF NOT EXISTS `session_presence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `joined_at` DATETIME NOT NULL,
    `left_at` DATETIME,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_session_presence_session_user_joined` (`session_id`, `user_id`, `joined_at`),
    CONSTRAINT `fk_session_presence_session` FOREIGN KEY (`session_id`) REFERENCES `learning_sessions` (`id`),
    CONSTRAINT `fk_session_presence_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `chk_session_presence_interval` CHECK (`left_at` IS NULL OR `joined_at` < `left_at`)
);

-- Add generated column and unique index to enforce one open presence interval per user per session
-- is_open = 1 when left_at IS NULL (open interval), NULL when left_at IS NOT NULL (closed interval)
-- Unique index on (session_id, user_id, is_open) allows only one open interval per user per session
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'session_presence' AND COLUMN_NAME = 'is_open') = 0,
    'ALTER TABLE `session_presence` ADD COLUMN `is_open` TINYINT GENERATED ALWAYS AS (CASE WHEN `left_at` IS NULL THEN 1 ELSE NULL END) STORED',
    'SELECT 1'
));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'session_presence' AND INDEX_NAME = 'uk_session_presence_open_interval') = 0,
    'ALTER TABLE `session_presence` ADD UNIQUE INDEX `uk_session_presence_open_interval` (`session_id`, `user_id`, `is_open`)',
    'SELECT 1'
));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
