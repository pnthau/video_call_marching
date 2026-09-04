-- V1: Initial schema (baseline for existing tables)
-- This represents the current state before Lifecycle V2 changes

CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(255) NOT NULL UNIQUE,
    `email` VARCHAR(255) NOT NULL UNIQUE,
    `current_level` VARCHAR(2) NOT NULL DEFAULT 'N5',
    `trust_score` FLOAT NOT NULL DEFAULT 0.0,
    `avatar_url` VARCHAR(500),
    `avatar_public_id` VARCHAR(255),
    `role` VARCHAR(20) NOT NULL DEFAULT 'USER',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `tag_categories` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `type` VARCHAR(20) NOT NULL,
    `active` BOOLEAN NOT NULL DEFAULT TRUE,
    `display_order` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_category_name` (`name`)
);

CREATE TABLE IF NOT EXISTS `tags` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `category_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`category_id`) REFERENCES `tag_categories`(`id`)
);

CREATE TABLE IF NOT EXISTS `learning_sessions` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `channel_name` VARCHAR(100) NOT NULL UNIQUE,
    `level_snapshot` VARCHAR(2) NOT NULL,
    `tag_snapshot` VARCHAR(100) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'MATCHED',
    `matched_at` DATETIME NOT NULL,
    `started_at` DATETIME,
    `ended_at` DATETIME,
    `user1_joined_agora_at` DATETIME,
    `user1_left_agora_at` DATETIME,
    `user2_joined_agora_at` DATETIME,
    `user2_left_agora_at` DATETIME,
    `overlapping_duration_seconds` INT,
    `completion_reason` VARCHAR(30),
    `user1_id` BIGINT NOT NULL,
    `user2_id` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`user1_id`) REFERENCES `users`(`id`),
    FOREIGN KEY (`user2_id`) REFERENCES `users`(`id`)
);

CREATE TABLE IF NOT EXISTS `peer_ratings` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `rater_id` BIGINT NOT NULL,
    `ratee_id` BIGINT NOT NULL,
    `total_score` INT NOT NULL,
    `created_at` DATETIME,
    PRIMARY KEY (`id`),
    FOREIGN KEY (`rater_id`) REFERENCES `users`(`id`),
    FOREIGN KEY (`ratee_id`) REFERENCES `users`(`id`)
);

CREATE TABLE IF NOT EXISTS `social_accounts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `provider` VARCHAR(255) NOT NULL,
    `provider_id` VARCHAR(255) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_social_account_provider_id` (`provider`, `provider_id`),
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
);
