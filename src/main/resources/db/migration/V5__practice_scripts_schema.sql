-- V5: Practice scripts, practice history, API keys, and legacy schema align

-- Tag Categories alignment
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag_categories' AND COLUMN_NAME = 'type') = 0,
    'ALTER TABLE `tag_categories` ADD COLUMN `type` VARCHAR(20) NOT NULL DEFAULT \'TOPIC\'',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag_categories' AND COLUMN_NAME = 'active') = 0,
    'ALTER TABLE `tag_categories` ADD COLUMN `active` BOOLEAN NOT NULL DEFAULT TRUE',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag_categories' AND COLUMN_NAME = 'display_order') = 0,
    'ALTER TABLE `tag_categories` ADD COLUMN `display_order` INT NOT NULL DEFAULT 0',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `tag_categories` SET `type` = 'LEVEL', `display_order` = 1 WHERE `name` LIKE '%Level%' OR `name` LIKE '%Trình độ%';
UPDATE `tag_categories` SET `type` = 'ACTIVITY', `display_order` = 2 WHERE `name` LIKE '%Format%' OR `name` LIKE '%Hình thức%';
UPDATE `tag_categories` SET `type` = 'TOPIC', `display_order` = 3 WHERE `name` LIKE '%Topic%' OR `name` LIKE '%Chủ đề%';

-- Users alignment
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'email') = 0,
    'ALTER TABLE `users` ADD COLUMN `email` VARCHAR(255) NULL',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `users` SET `email` = CONCAT(`username`, '@example.com') WHERE `email` IS NULL OR `email` = '';
ALTER TABLE `users` MODIFY COLUMN `email` VARCHAR(255) NOT NULL;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_users_email') = 0,
    'ALTER TABLE `users` ADD UNIQUE KEY `uk_users_email` (`email`)',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'current_level') = 'int',
    'ALTER TABLE `users` MODIFY COLUMN `current_level` VARCHAR(2) NOT NULL DEFAULT \'N5\'',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'avatar_public_id') = 0,
    'ALTER TABLE `users` ADD COLUMN `avatar_public_id` VARCHAR(255)',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role') = 0,
    'ALTER TABLE `users` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT \'USER\'',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status') = 0,
    'ALTER TABLE `users` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT \'ACTIVE\'',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'created_at') = 0,
    'ALTER TABLE `users` ADD COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'updated_at') = 0,
    'ALTER TABLE `users` ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Scripts, Practice Histories, and API keys
CREATE TABLE IF NOT EXISTS `scripts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tag_id` BIGINT NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `content` TEXT NOT NULL,
    `language` VARCHAR(255) NOT NULL,
    `phonetic` TEXT,
    `meaning_content` TEXT,
    `target_duration` INT,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`tag_id`) REFERENCES `tags`(`id`)
);

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

CREATE TABLE IF NOT EXISTS `api_keys` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `provider` VARCHAR(255),
    `api_key` VARCHAR(255),
    `active` BOOLEAN,
    `created_at` DATETIME,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
);
