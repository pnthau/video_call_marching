# Spec: MVP Domain & Current Baseline

## 1. Trạng thái

- Trạng thái: **APPROVED BASELINE — cập nhật theo source ngày 2026-08-25**.
- Package giữ nguyên: `com.example.videocall_marching_language`.
- Feature mới theo `Repository -> Service -> ServiceImpl -> Controller`, dùng DTO ở Controller.
- Không đổi package hoặc refactor ngoài phạm vi spec feature.

## 2. Luồng và phạm vi MVP

`Google login -> profile/level -> chọn tag -> queue -> ghép cặp 1-1 -> video call -> kết thúc session -> đánh giá 7 tiêu chí`

Ngoài phạm vi: phone/password, OTP, JWT, account linking, Redis/distributed matching,
buddy, group call, recording, tự động nâng level và social login ngoài Google.

## 3. Ma trận hiện trạng

### DONE

- Google OIDC, Spring Security, HTTP Session/`JSESSIONID`, logout và CSRF baseline.
- User, SocialAccount, profile, level N5-N1 và avatar Cloudinary.
- Repository, service/service-impl, controller và DTO cho phần đã triển khai.
- Matchmaking WebSocket cơ bản và persistent `LearningSession`.
- Participant authorization, session-based Agora token, history và lifecycle write lock.

### PARTIAL

- Queue/result matchmaking còn in-memory và chưa bảo vệ đầy đủ concurrent matching.
- Lifecycle hiện là `MATCHED -> IN_PROGRESS -> ENDED`, chưa phân biệt kết quả hoàn tất.
- Presence chỉ có một bộ timestamp join/leave, chưa hỗ trợ đầy đủ nhiều lần reconnect.
- WebSocket disconnect có thể kết thúc session ngay, chưa thực thi grace period.
- Frontend chưa có CSRF contract hoàn chỉnh cho POST lifecycle.

### MISSING

- Scheduler/finalizer cho reconnect grace, match timeout và max session duration.
- Rating detail đủ 7 rubric và submit-rating flow.
- End-to-end integration test cho toàn luồng MVP.

## 4. Quyết định domain

### 4.1. Authentication

- MVP chính thức là Google-only theo `spec_user_authentication.md`.
- Identity luôn lấy từ `Authentication`/`Principal`; không tin `userId` từ client.
- Hai role: `USER`, `ADMIN`.

### 4.2. Level và snapshot

- Level: `N5 -> N4 -> N3 -> N2 -> N1`; MVP không tự nâng level.
- Match ưu tiên cùng level, mở rộng tối đa một level liền kề sau timeout cấu hình.
- Level/tag được snapshot khi match; thay đổi profile không sửa session cũ.

### 4.3. Matchmaking và lifecycle

- `WAITING` thuộc queue/match request, không thuộc `LearningSession`.
- Chỉ tạo session sau khi có hai user tương thích.
- Vocabulary mục tiêu:

  `MATCHED -> IN_PROGRESS -> COMPLETED | INCOMPLETE`

  và `MATCHED -> CANCELLED`.
- `ENDED` hiện tại là trạng thái cần migrate, không phải vocabulary đích.
- Một user có tối đa một queue entry hoặc active session.
- Hai participant khác nhau; `channelName` unique và do backend sinh.

### 4.4. Completion và presence

- Minimum overlapping presence: **300 giây (5 phút)**.
- Reconnect grace: **60 giây**.
- Maximum session duration: **3600 giây (60 phút)**.
- Các giá trị phải cấu hình tập trung, không hardcode rải rác.
- `IN_PROGRESS` bắt đầu khi cả hai participant join Agora thành công.
- `COMPLETED`: từng vào `IN_PROGRESS`, tổng overlap >= 300 giây và kết thúc hợp lệ.
- `INCOMPLETE`: đã bắt đầu nhưng overlap < 300 giây hoặc lỗi trước ngưỡng.
- Join/leave idempotent; tổng overlap hỗ trợ nhiều khoảng reconnect.
- Rating không phải điều kiện hoàn tất session.

### 4.5. Agora

- Agora chỉ là transport; `LearningSession` sở hữu quyền vào channel.
- Client chỉ gửi `sessionId`.
- Backend lấy current user từ SecurityContext, kiểm tra participant/status, lấy channel từ
  database rồi sinh UID/token.
- Không lưu token; không cấp token mới cho terminal session.

### 4.6. Rubric và PeerRating

7 mã rubric MVP, mỗi tiêu chí 1-5:

1. `ACCURACY`
2. `FLUENCY`
3. `PRONUNCIATION_INTONATION`
4. `STRUCTURE_LOGIC`
5. `CONTENT_INTERESTINGNESS`
6. `BODY_LANGUAGE`
7. `ENTHUSIASM_CONFIDENCE`

- Không có `SUPPORTIVENESS`.
- Backend tính `totalScore` 7-35; lưu từng detail.
- Chỉ participant của session `COMPLETED` được rating peer.
- Không tự đánh giá; unique `(sessionId, raterId, rateeId)`.

## 5. Security baseline

- Controller không trả entity trực tiếp.
- Không tin `userId`, `uid`, `channelName` từ client cho authorization.
- Validate state transition; lifecycle mutation có concurrency control.
- POST join/leave/end/rating tuân thủ CSRF.
- Queue/session commands phải an toàn khi retry.

## 6. Thứ tự triển khai

1. `spec_learning_session_matchmaking_v2.md`.
2. `spec_agora_token.md`.
3. `spec_frontend_video_call.md`.
4. Tạo `spec_rubric_peer_rating.md` trước khi làm rating.
5. Integration/security/production hardening.

## 7. Acceptance criteria

- [x] Baseline phản ánh source ngày 2026-08-25.
- [x] Authentication là Google-only.
- [x] `WAITING` thuộc matchmaking.
- [x] Trạng thái đích là `MATCHED/IN_PROGRESS/COMPLETED/INCOMPLETE/CANCELLED`.
- [x] Overlap 300 giây, grace 60 giây, max duration 3600 giây.
- [x] Rubric MVP có 7 tiêu chí.
- [x] `gradlew test` thành công ngày 2026-08-25.
