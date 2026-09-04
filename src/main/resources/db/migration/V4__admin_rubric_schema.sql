CREATE TABLE `rubrics` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `criteria` VARCHAR(40) NOT NULL,
    `display_name` VARCHAR(100) NOT NULL,
    `description` VARCHAR(1000) NULL,
    `is_active` BOOLEAN NOT NULL DEFAULT TRUE,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_rubrics_criteria` UNIQUE (`criteria`),
    CONSTRAINT `chk_rubrics_criteria_whitelist` CHECK (`criteria` IN (
        'ACCURACY',
        'FLUENCY',
        'PRONUNCIATION_INTONATION',
        'STRUCTURE_LOGIC',
        'CONTENT_INTERESTINGNESS',
        'BODY_LANGUAGE',
        'ENTHUSIASM_CONFIDENCE'
    ))
);
