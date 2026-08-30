-- V3: Lifecycle V2 data migration
-- Migrates legacy ENDED sessions to COMPLETED/INCOMPLETE based on overlap evidence
-- Creates SessionPresence rows from legacy timestamps where valid
-- Idempotent: safe to run multiple times
--
-- ROLLBACK NOTES:
-- This migration is NOT easily reversible because it transforms legacy data.
-- To rollback, you would need to:
-- 1. Restore from backup (recommended)
-- 2. Or manually reverse: UPDATE learning_sessions SET status='ENDED', completion_reason=legacy_reason WHERE status IN ('COMPLETED','INCOMPLETE');
-- 3. And DELETE FROM session_presence WHERE session_id IN (SELECT id FROM learning_sessions WHERE status IN ('COMPLETED','INCOMPLETE'));
--
-- DRY-RUN:
-- To preview changes without applying, run with flyway.dryRunOutput=target/migration-dryrun.sql
-- Or manually execute SELECT statements from this script to see affected rows.

-- 1. Create a temporary table to track migration stats
CREATE TEMPORARY TABLE IF NOT EXISTS `migration_stats` (
    `legacy_status` VARCHAR(20),
    `legacy_reason` VARCHAR(30),
    `before_count` INT,
    `after_completed` INT,
    `after_incomplete` INT,
    `after_cancelled` INT
);

-- Clear any existing stats (idempotent)
DELETE FROM `migration_stats`;

-- 2. Insert stats for legacy ENDED sessions
INSERT INTO `migration_stats` (`legacy_status`, `legacy_reason`, `before_count`, `after_completed`, `after_incomplete`, `after_cancelled`)
SELECT
    'ENDED' as legacy_status,
    COALESCE(`completion_reason`, 'UNKNOWN') as legacy_reason,
    COUNT(*) as before_count,
    0, 0, 0
FROM `learning_sessions`
WHERE `status` = 'ENDED'
GROUP BY `completion_reason`;

-- 3. Migrate ENDED -> COMPLETED where:
--    - Has two valid, closed legacy presence intervals
--    - Their calculated overlap is at least 300 seconds
--    - Legacy reason is not ERROR
UPDATE `learning_sessions`
SET
    `status` = 'COMPLETED',
    `completion_reason` = CASE
        WHEN `completion_reason` = 'NORMAL' THEN 'BOTH_LEFT'
        WHEN `completion_reason` = 'PEER_LEFT' THEN 'ONE_LEFT_TIMEOUT'
        WHEN `completion_reason` = 'TIMEOUT' THEN
            CASE
                WHEN TIMESTAMPDIFF(SECOND, `started_at`, `ended_at`) >= 3600 THEN 'MAX_DURATION_REACHED'
                ELSE 'ONE_LEFT_TIMEOUT'
            END
        ELSE 'BOTH_LEFT'
    END,
    `accumulated_overlap_seconds` = TIMESTAMPDIFF(
        SECOND,
        GREATEST(`user1_joined_agora_at`, `user2_joined_agora_at`),
        LEAST(`user1_left_agora_at`, `user2_left_agora_at`)
    )
WHERE `status` = 'ENDED'
  AND `user1_joined_agora_at` IS NOT NULL
  AND `user2_joined_agora_at` IS NOT NULL
  AND `user1_left_agora_at` IS NOT NULL
  AND `user2_left_agora_at` IS NOT NULL
  AND `user1_joined_agora_at` < `user1_left_agora_at`
  AND `user2_joined_agora_at` < `user2_left_agora_at`
  AND TIMESTAMPDIFF(
        SECOND,
        GREATEST(`user1_joined_agora_at`, `user2_joined_agora_at`),
        LEAST(`user1_left_agora_at`, `user2_left_agora_at`)
      ) >= 300
  AND COALESCE(`completion_reason`, '') != 'ERROR';

-- 4. Migrate ENDED -> INCOMPLETE where:
--    - Every remaining ENDED row lacks sufficient valid evidence or ended with ERROR
UPDATE `learning_sessions`
SET
    `status` = 'INCOMPLETE',
    `completion_reason` = CASE
        WHEN `completion_reason` = 'ERROR' THEN 'TECHNICAL_FAILURE'
        WHEN `user1_joined_agora_at` IS NULL OR `user2_joined_agora_at` IS NULL THEN 'INSUFFICIENT_DURATION'
        WHEN COALESCE(`overlapping_duration_seconds`, 0) < 300 THEN 'INSUFFICIENT_DURATION'
        ELSE 'INSUFFICIENT_DURATION'
    END,
    `accumulated_overlap_seconds` = CASE
        WHEN `user1_joined_agora_at` IS NOT NULL
         AND `user2_joined_agora_at` IS NOT NULL
         AND `user1_left_agora_at` IS NOT NULL
         AND `user2_left_agora_at` IS NOT NULL
         AND `user1_joined_agora_at` < `user1_left_agora_at`
         AND `user2_joined_agora_at` < `user2_left_agora_at`
        THEN GREATEST(0, TIMESTAMPDIFF(
            SECOND,
            GREATEST(`user1_joined_agora_at`, `user2_joined_agora_at`),
            LEAST(`user1_left_agora_at`, `user2_left_agora_at`)
        ))
        ELSE 0
    END
WHERE `status` = 'ENDED';

-- 5. Create SessionPresence rows from legacy timestamps (only when both joined_at are valid)
-- Use INSERT IGNORE to make idempotent (won't insert duplicates due to unique index)

-- User 1 closed intervals
INSERT IGNORE INTO `session_presence` (`session_id`, `user_id`, `joined_at`, `left_at`, `created_at`)
SELECT
    `id`, `user1_id`, `user1_joined_agora_at`, `user1_left_agora_at`, `created_at`
FROM `learning_sessions` ls
WHERE `user1_joined_agora_at` IS NOT NULL
  AND `user1_left_agora_at` IS NOT NULL
  AND `user1_joined_agora_at` < `user1_left_agora_at`
  AND NOT EXISTS (
      SELECT 1 FROM `session_presence` sp
      WHERE sp.`session_id` = ls.`id` AND sp.`user_id` = ls.`user1_id`
        AND sp.`joined_at` = ls.`user1_joined_agora_at`
        AND sp.`left_at` = ls.`user1_left_agora_at`
  );

-- User 1 open interval (joined but not left) - only if no open interval exists for this user/session
INSERT IGNORE INTO `session_presence` (`session_id`, `user_id`, `joined_at`, `left_at`, `created_at`)
SELECT
    `id`, `user1_id`, `user1_joined_agora_at`, NULL, `created_at`
FROM `learning_sessions` ls
WHERE `user1_joined_agora_at` IS NOT NULL
  AND `user1_left_agora_at` IS NULL
  AND `status` IN ('MATCHED', 'IN_PROGRESS')
  AND NOT EXISTS (
      SELECT 1 FROM `session_presence` sp
      WHERE sp.`session_id` = ls.`id`
      AND sp.`user_id` = ls.`user1_id`
      AND sp.`left_at` IS NULL
  );

-- User 2 closed intervals
INSERT IGNORE INTO `session_presence` (`session_id`, `user_id`, `joined_at`, `left_at`, `created_at`)
SELECT
    `id`, `user2_id`, `user2_joined_agora_at`, `user2_left_agora_at`, `created_at`
FROM `learning_sessions` ls
WHERE `user2_joined_agora_at` IS NOT NULL
  AND `user2_left_agora_at` IS NOT NULL
  AND `user2_joined_agora_at` < `user2_left_agora_at`
  AND NOT EXISTS (
      SELECT 1 FROM `session_presence` sp
      WHERE sp.`session_id` = ls.`id` AND sp.`user_id` = ls.`user2_id`
        AND sp.`joined_at` = ls.`user2_joined_agora_at`
        AND sp.`left_at` = ls.`user2_left_agora_at`
  );

-- User 2 open interval (joined but not left) - only if no open interval exists for this user/session
INSERT IGNORE INTO `session_presence` (`session_id`, `user_id`, `joined_at`, `left_at`, `created_at`)
SELECT
    `id`, `user2_id`, `user2_joined_agora_at`, NULL, `created_at`
FROM `learning_sessions` ls
WHERE `user2_joined_agora_at` IS NOT NULL
  AND `user2_left_agora_at` IS NULL
  AND `status` IN ('MATCHED', 'IN_PROGRESS')
  AND NOT EXISTS (
      SELECT 1 FROM `session_presence` sp
      WHERE sp.`session_id` = ls.`id`
      AND sp.`user_id` = ls.`user2_id`
      AND sp.`left_at` IS NULL
  );

-- 6. Update migration stats
UPDATE `migration_stats` ms
SET
    `after_completed` = (
        SELECT COUNT(*) FROM `learning_sessions` ls
        WHERE ls.`status` = 'COMPLETED'
        AND (ms.`legacy_reason` = 'UNKNOWN' OR ls.`completion_reason` IN (
            SELECT CASE
                WHEN ms.`legacy_reason` = 'NORMAL' THEN 'BOTH_LEFT'
                WHEN ms.`legacy_reason` = 'PEER_LEFT' THEN 'ONE_LEFT_TIMEOUT'
                WHEN ms.`legacy_reason` = 'TIMEOUT' THEN
                    CASE
                        WHEN TIMESTAMPDIFF(SECOND, ls.`started_at`, ls.`ended_at`) >= 3600 THEN 'MAX_DURATION_REACHED'
                        ELSE 'ONE_LEFT_TIMEOUT'
                    END
                ELSE 'BOTH_LEFT'
            END
        ))
    ),
    `after_incomplete` = (
        SELECT COUNT(*) FROM `learning_sessions` ls
        WHERE ls.`status` = 'INCOMPLETE'
        AND (ms.`legacy_reason` = 'UNKNOWN' OR ls.`completion_reason` IN (
            SELECT CASE
                WHEN ms.`legacy_reason` = 'ERROR' THEN 'TECHNICAL_FAILURE'
                ELSE 'INSUFFICIENT_DURATION'
            END
        ))
    );

-- 7. Output migration report
SELECT * FROM `migration_stats`;

-- 8. Cleanup
DROP TEMPORARY TABLE IF EXISTS `migration_stats`;
