INSERT INTO users(username,email,current_level,trust_score,role,status,created_at,updated_at) VALUES
('legacy-a','legacy-a@invalid.example','N5',0,'USER','ACTIVE',NOW(),NOW()),
('legacy-b','legacy-b@invalid.example','N5',0,'USER','ACTIVE',NOW(),NOW());

SET @u1=(SELECT id FROM users WHERE email='legacy-a@invalid.example');
SET @u2=(SELECT id FROM users WHERE email='legacy-b@invalid.example');

INSERT INTO learning_sessions(
    channel_name,level_snapshot,tag_snapshot,status,matched_at,started_at,ended_at,
    user1_joined_agora_at,user1_left_agora_at,user2_joined_agora_at,user2_left_agora_at,
    overlapping_duration_seconds,completion_reason,user1_id,user2_id,created_at,updated_at
) VALUES
('legacy_matched','N5','fixture','MATCHED','2026-01-01 00:00:00',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,@u1,@u2,NOW(),NOW()),
('legacy_in_progress','N5','fixture','IN_PROGRESS','2026-01-01 00:00:00','2026-01-01 00:00:10',NULL,'2026-01-01 00:00:00',NULL,'2026-01-01 00:00:10',NULL,NULL,NULL,@u1,@u2,NOW(),NOW()),
('legacy_normal_300','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:05:10','2026-01-01 00:00:00','2026-01-01 00:05:00','2026-01-01 00:00:00','2026-01-01 00:05:00',300,'NORMAL',@u1,@u2,NOW(),NOW()),
('legacy_peer_300','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:05:10','2026-01-01 00:00:00','2026-01-01 00:05:00','2026-01-01 00:00:00','2026-01-01 00:05:00',300,'PEER_LEFT',@u1,@u2,NOW(),NOW()),
('legacy_timeout_max','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 01:00:00','2026-01-01 00:00:00','2026-01-01 00:05:00','2026-01-01 00:00:00','2026-01-01 00:05:00',300,'TIMEOUT',@u1,@u2,NOW(),NOW()),
('legacy_timeout_short','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:10:00','2026-01-01 00:00:00','2026-01-01 00:05:00','2026-01-01 00:00:00','2026-01-01 00:05:00',300,'TIMEOUT',@u1,@u2,NOW(),NOW()),
('legacy_error','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:06:40','2026-01-01 00:00:00','2026-01-01 00:06:40','2026-01-01 00:00:00','2026-01-01 00:06:40',400,'ERROR',@u1,@u2,NOW(),NOW()),
('legacy_under_299','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:04:59','2026-01-01 00:00:00','2026-01-01 00:04:59','2026-01-01 00:00:00','2026-01-01 00:04:59',299,'NORMAL',@u1,@u2,NOW(),NOW()),
('legacy_missing','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:06:00','2026-01-01 00:00:00',NULL,'2026-01-01 00:00:00','2026-01-01 00:06:00',360,'NORMAL',@u1,@u2,NOW(),NOW()),
('legacy_invalid','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:06:00','2026-01-01 00:06:00','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:06:00',360,'NORMAL',@u1,@u2,NOW(),NOW()),
('legacy_no_overlap','N5','fixture','ENDED','2026-01-01 00:00:00','2026-01-01 00:00:00','2026-01-01 00:20:00','2026-01-01 00:00:00','2026-01-01 00:05:00','2026-01-01 00:10:00','2026-01-01 00:20:00',600,'NORMAL',@u1,@u2,NOW(),NOW());
