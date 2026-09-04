# Spec: Matchmaking & LearningSession Lifecycle V2

## 1. Trạng thái

* **IMPLEMENTED — Giai đoạn 1 nghiệm thu PASS ngày 2026-08-30**.
* Baseline nghiệm thu: MySQL 8.0.46, Flyway V1-V3 và Hibernate schema validation.
* Phạm vi: queue/matching, lifecycle, presence/reconnect, completion và migration dữ liệu lifecycle hiện có.
* Không triển khai rating trong spec này.
* Automated gate cuối: 116 tests, 0 failures/errors/skipped; build PASS.
* Manual gate: two-browser lifecycle/recovery, Profile C authorization, scheduler finalization và WebSocket terminal notification đều PASS.

## 2. Mục tiêu và phạm vi

`Authenticated user -> queue -> atomic 1-1 match -> LearningSession -> both join -> track overlap/reconnect -> finalize`

Trong phạm vi:

* Queue in-memory trên một application instance.
* Matching theo level và tag.
* Persistent `LearningSession`.
* Participant authorization.
* Presence và reconnect.
* Timeout và finalization.
* Recovery sau khi server restart.
* Session history.

Ngoài phạm vi:

* Redis hoặc multi-instance matching.
* Group call.
* Buddy.
* Recording.
* Rating.
* Thay đổi authentication.

## 3. Quyết định đã chốt

1. Dùng bảng `SessionPresence` để lưu từng khoảng join/leave của từng participant.

2. Session terminal trả `409 Conflict` khi client yêu cầu token, join hoặc thực hiện lifecycle transition không hợp lệ.

3. Giữ các giá trị mặc định:

   * Minimum overlap: 300 giây.
   * Reconnect grace: 60 giây.
   * Maximum duration: 3600 giây.
   * Adjacent-level expansion: 120 giây.
   * Match timeout: 600 giây.

4. Legacy `ENDED` chỉ chuyển thành `COMPLETED` khi dữ liệu chứng minh overlap tối thiểu 300 giây và không kết thúc do lỗi.

5. Legacy session không đủ dữ liệu để chứng minh overlap chuyển thành `INCOMPLETE`.

6. Matching ưu tiên cùng level, tag giao nhau và người chờ lâu nhất.

7. Chỉ mở rộng sang level liền kề khi user làm anchor đã chờ đủ 120 giây.

8. Khi cả hai participant đều absent, session finalize ngay với `BOTH_LEFT`; reconnect grace chỉ áp dụng khi còn đúng một participant present.

9. Client không được quyết định identity, `channelName` hoặc Agora UID. Quy tắc này tiếp tục áp dụng khi mở rộng group call/invitation; client chỉ gửi ý định nghiệp vụ, backend quản lý membership, channel và UID.

10. Boundary thời gian là inclusive: adjacent level khi chờ `>= 120` giây, completion khi overlap `>= 300` giây, timeout khi `now >= deadline`; rejoin tại đúng reconnect deadline vẫn hợp lệ.

## 4. MatchingQueueEntry

`WAITING` thuộc queue entry, không thuộc `LearningSession`.

Mỗi entry chứa tối thiểu:

* `userId`.
* Level snapshot.
* Tag snapshot.
* `enqueuedAt`.
* Connection reference.

Quy tắc:

* Một user có tối đa một queue entry.
* User có active session không được vào queue.
* Active session gồm `MATCHED` và `IN_PROGRESS`.
* Cancel, retry và disconnect phải idempotent.
* Compound match operation chạy trong một critical section của process.
* Queue entry chỉ bị loại vĩnh viễn sau khi transaction tạo session commit thành công.

## 5. LearningSession

Một `LearningSession` chỉ được tạo sau khi ghép được hai user tương thích.

Quy tắc:

* Hai participant phải khác nhau.
* `channelName` là unique.
* `channelName` do backend sinh.
* `channelName` không chứa PII trực tiếp.
* Lưu level và tag snapshot tại thời điểm match.
* Lưu `matchedAt`.
* Lưu `startedAt`, `endedAt`.
* Lưu `completionReason`.
* Lưu `accumulatedOverlapSeconds`.
* Lưu `reconnectDeadline`.
* Lifecycle mutation sử dụng version hoặc pessimistic write lock.

Lifecycle:

```text
MATCHED
   |-- both joined ----------------> IN_PROGRESS
   |-- cancelled/match timeout ----> CANCELLED

IN_PROGRESS
   |-- valid end + overlap >= 300s -> COMPLETED
   |-- end/error + overlap < 300s --> INCOMPLETE
```

Các terminal state:

* `COMPLETED`.
* `INCOMPLETE`.
* `CANCELLED`.

Terminal state không được chuyển ngược về active state.

Completion reason:

* `BOTH_LEFT`.
* `ONE_LEFT_TIMEOUT`.
* `MAX_DURATION_REACHED`.
* `INSUFFICIENT_DURATION`.
* `TECHNICAL_FAILURE`.
* `MATCH_TIMEOUT`.
* `CANCELLED_BY_USER`.
* `CANCELLED_BY_SYSTEM`.

Không tiếp tục sử dụng reason mơ hồ như `NORMAL` hoặc `ERROR`.

## 6. Cấu hình

```properties
learning-session.minimum-overlap-seconds=300
learning-session.reconnect-grace-seconds=60
learning-session.maximum-duration-seconds=3600
matching.adjacent-level-after-seconds=120
matching.match-timeout-seconds=600
```

Tên property và giá trị mặc định trên là contract của Lifecycle V2.

Nếu đổi tên hoặc giá trị, phải cập nhật đồng thời:

* Spec.
* Configuration binding.
* Unit test.
* Integration test.

## 7. Thuật toán matching

### 7.1. Điều kiện tương thích

* Identity và level lấy từ authenticated user.

* Không tin `userId`, level hoặc identity do client gửi.

* Hai queue entry tương thích tag khi tag snapshot có ít nhất một tag chung.

* Không self-match.

* Cả hai user không được có active session.

* Việc kiểm tra active session được thực hiện trong queue và kiểm tra lại trong transaction.

* Level liền kề theo thứ tự:

  `N5 -> N4 -> N3 -> N2 -> N1`

* Không mở rộng quá một level.

### 7.2. Chọn cặp

Trong critical section:

1. Chọn anchor có `enqueuedAt` nhỏ nhất.
2. Nếu nhiều entry có cùng `enqueuedAt`, chọn `userId` nhỏ hơn làm anchor.
3. Tìm candidate có tag tương thích và cùng level.
4. Nếu không có candidate cùng level và anchor đã chờ ít nhất 120 giây, tìm candidate ở level liền kề.
5. Trong cùng nhóm ưu tiên level, chọn candidate có số tag giao nhau nhiều nhất.
6. Nếu vẫn hòa, chọn candidate có `enqueuedAt` nhỏ nhất.
7. Nếu vẫn hòa, dùng `userId` tăng dần làm tie-breaker.
8. Kiểm tra lại queue uniqueness và active-session conflict trong transaction.
9. Persist `LearningSession`.
10. Chỉ loại hai queue entry và publish `MATCHED` sau khi transaction commit thành công.

### 7.3. Khi persist thất bại

* Transaction tạo `LearningSession` phải rollback hoàn toàn.
* Không được publish sự kiện `MATCHED`.
* Hai queue entry được giữ lại hoặc khôi phục trong critical section.
* Entry được khôi phục phải giữ nguyên `enqueuedAt` ban đầu.
* Không reset thời gian chờ vì lỗi database.
* Nếu kiểm tra lại cho thấy một user đã cancel hoặc có active session, chỉ loại entry không còn hợp lệ.
* Không swallow exception.
* Log session/user context nhưng không log token hoặc secret.

## 8. SessionPresence và overlap

### 8.1. Mô hình persistence

Mỗi lần participant hiện diện trong session tạo một row:

```text
SessionPresence
- id
- sessionId
- userId
- joinedAt
- leftAt
```

Quy tắc:

* `leftAt` nullable khi interval đang mở.
* Nếu có `leftAt` thì `joinedAt < leftAt`.
* `userId` phải là participant của session.
* Mỗi `(sessionId, userId)` chỉ có tối đa một interval đang mở.
* Tạo index `(sessionId, userId, joinedAt)`.
* Presence rows là dữ liệu audit và source of truth.
* `accumulatedOverlapSeconds` trên `LearningSession` là aggregate để truy vấn nhanh.
* Aggregate phải được cập nhật transactionally.
* Khi finalize, service phải tính hoặc đối chiếu overlap từ các interval đã đóng trước khi ghi terminal state.

### 8.2. Join

* Chỉ participant được join.

* Session phải ở trạng thái `MATCHED` hoặc `IN_PROGRESS`.

* Join lặp khi participant đã có interval mở là no-op.

* Duplicate join trả trạng thái hiện tại và không tạo interval mới.

* Join session terminal trả `409 Conflict`.

* Khi cả hai participant present lần đầu:

  `MATCHED -> IN_PROGRESS`

* Đặt `startedAt` tại thời điểm cả hai cùng present lần đầu.

* Rejoin hợp lệ sẽ xóa `reconnectDeadline`.

* Rejoin mở một presence interval mới.

### 8.3. Leave và reconnect

* Leave lặp khi participant không có interval mở là no-op.

* Leave đóng interval hiện tại bằng cách đặt `leftAt`.

* Khi một participant vắng mặt, đóng khoảng overlap đang mở.

* Nếu cả hai participant đều absent, finalize ngay với `BOTH_LEFT`, không chờ reconnect grace.

* Đặt:

  `reconnectDeadline = now + 60 seconds`

* Rejoin trước hoặc đúng deadline tiếp tục session.

* Khi cả hai present trở lại, mở khoảng overlap mới.

* Sau deadline, finalizer kết thúc session bằng `ONE_LEFT_TIMEOUT`.

* Nếu request rejoin đến sau deadline nhưng scheduler chưa chạy, service phải finalize session trước rồi trả `409 Conflict`.

* WebSocket disconnect chỉ là presence signal.

* WebSocket disconnect không kết thúc session ngay.

## 9. Finalization

### 9.1. COMPLETED

Session chuyển thành `COMPLETED` khi:

* Đã từng vào `IN_PROGRESS`.
* Tổng overlap ít nhất 300 giây.
* Không kết thúc do technical failure.
* Kết thúc bởi sự kiện hợp lệ.

### 9.2. INCOMPLETE

Session chuyển thành `INCOMPLETE` khi:

* Đã từng vào `IN_PROGRESS`.
* Tổng overlap dưới 300 giây; hoặc
* Kết thúc do technical failure.

Technical failure luôn tạo kết quả `INCOMPLETE`, kể cả khi thời gian đã đạt 300 giây.

### 9.3. CANCELLED

Session chuyển thành `CANCELLED` khi:

* Chưa từng vào `IN_PROGRESS`; và
* Bị user cancel, system cancel hoặc match timeout.

### 9.4. Maximum duration

* Maximum duration được tính từ `startedAt`.
* Khi session đạt 3600 giây, scheduler finalize bằng `MAX_DURATION_REACHED`.
* Nếu overlap đạt 300 giây và không có technical failure, kết quả là `COMPLETED`.
* Nếu overlap dưới 300 giây, kết quả là `INCOMPLETE`.

### 9.5. Concurrency

* Scheduler, join, leave và disconnect có thể chạy đồng thời.

* Mọi finalization phải chạy trong transaction với write lock.

* Session chỉ được finalize đúng một lần.

* Terminal state phải lưu:

  * `endedAt`.
  * `completionReason`.
  * Tổng overlap cuối cùng.

* Finalization lặp với cùng kết quả là no-op.

* Mutation yêu cầu mở lại terminal session trả `409 Conflict`.

## 10. API, WebSocket và HTTP contract

* STOMP join/cancel không sử dụng client identity để authorization.

* Identity lấy từ HTTP session và authenticated principal.

* `GET /api/sessions/{id}` chỉ dành cho participant.

* `GET /api/sessions/{id}/token` tuân theo `spec_agora_token.md`.

* POST `join-agora` và `leave-agora`:

  * Participant-only.
  * Có CSRF.
  * Idempotent.

* Controller trả DTO.

* Controller không trả entity trực tiếp.

HTTP contract:

* Chưa authentication: `401 Unauthorized` hoặc redirect theo Spring Security entry point.
* Session không tồn tại: `404 Not Found`.
* Authenticated non-participant: `403 Forbidden`.
* Token/join hoặc lifecycle transition không hợp lệ trên terminal session: `409 Conflict`.
* Payload không hợp lệ: `400 Bad Request`.

## 11. Concurrency và recovery

* Queue phải thread-safe.

* Compound matching dùng một critical section rõ ràng.

* Active-session query và lifecycle mutation dùng transaction/write lock.

* Database schema cần constraint/index hỗ trợ phát hiện duplicate active session ở mức phù hợp.

* Service vẫn phải kiểm tra active-session conflict trong transaction.

* WebSocket notification thất bại không rollback session đã commit.

* Client recovery bằng cách gọi lại API lấy session hiện tại.

* Sau restart, scheduler đọc từ database:

  * `reconnectDeadline`.
  * `matchedAt`.
  * `startedAt`.
  * Active status.

* Scheduler tiếp tục xử lý session chưa finalize.

* Open presence interval được giữ làm source of truth.

* Recovery không tự tạo `leftAt` giả.

* Presence reconciliation dựa trên kết nối hiện tại hoặc timeout/finalizer.

* Logic thời gian dùng injectable `Clock` để unit test boundary một cách xác định.

## 12. Test matrix

### 12.1. Matching

* Same level và có tag chung thì match.
* Không có tag chung thì không match.
* Adjacent level chỉ được dùng sau khi anchor chờ đủ 120 giây.
* Candidate có nhiều tag chung hơn được ưu tiên.
* Nếu cùng số tag chung, người chờ lâu hơn được ưu tiên.
* Nếu vẫn hòa, dùng `userId`.
* Không self-match.
* Không duplicate queue.
* Không duplicate active session.
* Concurrent request không ghép một user vào hai session.
* Persist fail khôi phục entry với thời gian chờ ban đầu.

### 12.2. Authorization và HTTP

* Participant A truy cập được.
* Participant B truy cập được.
* User C nhận `403 Forbidden`.
* Session không tồn tại nhận `404 Not Found`.
* Client không override identity, channel hoặc UID.
* Token/join trên terminal session nhận `409 Conflict`.
* POST thiếu hoặc sai CSRF bị từ chối.

### 12.3. Lifecycle

* Chỉ cả hai join mới chuyển sang `IN_PROGRESS`.
* Duplicate join là no-op.
* Duplicate leave là no-op.
* Mỗi reconnect tạo presence interval mới.
* Nhiều lần reconnect tính đúng tổng overlap.
* Reconnect trong 60 giây giữ session.
* Reconnect sau deadline bị từ chối.
* Quá grace period chỉ finalize đúng một lần.
* Overlap dưới 300 giây là `INCOMPLETE`.
* Overlap từ 300 giây là `COMPLETED`.
* Technical failure luôn là `INCOMPLETE`.
* Maximum duration tự finalize.
* Terminal state không reopen.
* Restart server vẫn xử lý được persisted deadline.

### 12.4. Integration

* History trả đúng peer cho cả user1 và user2.
* Full flow chạy được với hai authenticated browser session.
* `.\\gradlew.bat test` thành công.
* `.\\gradlew.bat build` thành công.

## 13. Migration từ lifecycle cũ

### 13.1. Status

* Legacy `MATCHED` giữ nguyên nếu dữ liệu hợp lệ.

* Legacy `IN_PROGRESS` giữ nguyên nếu dữ liệu hợp lệ.

* Legacy `ENDED` chuyển thành `COMPLETED` chỉ khi:

  * Có đủ timestamp để chứng minh overlap ít nhất 300 giây; và
  * Legacy reason không phải `ERROR`.

* Legacy `ENDED` chuyển thành `INCOMPLETE` nếu:

  * Overlap dưới 300 giây.
  * Thiếu timestamp.
  * Timestamp không hợp lệ.
  * Không chứng minh được hai user cùng present.
  * Legacy reason là `ERROR`.

* Không suy đoán overlap từ tổng thời lượng session nếu không chứng minh được cả hai participant cùng present.

### 13.2. Completion reason

* Legacy `ERROR` → `TECHNICAL_FAILURE`.
* Legacy session không đủ hoặc không chứng minh được overlap → `INSUFFICIENT_DURATION`.
* Legacy `NORMAL` đủ overlap → `BOTH_LEFT`.
* Legacy `PEER_LEFT` đủ overlap → `ONE_LEFT_TIMEOUT`.
* Legacy `TIMEOUT` đủ overlap:

  * `MAX_DURATION_REACHED` nếu `endedAt - startedAt >= 3600 giây`.
  * Ngược lại là `ONE_LEFT_TIMEOUT`.

### 13.3. Presence

* Chỉ tạo `SessionPresence` từ timestamp cũ khi xác định được `joinedAt` hợp lệ.
* Chỉ ghi `leftAt` khi timestamp kết thúc tồn tại và lớn hơn `joinedAt`.
* Không tạo interval giả để làm session đủ 300 giây.
* Migration phải idempotent.
* Migration phải báo cáo số row theo status/reason trước và sau migration.
* Backup database trước production migration.
* Chạy dry-run trên bản sao dữ liệu trước khi migrate production.

### 13.4. Dry-run và rollback

* Dry-run trên bản sao phải chạy migration hai lần và đối chiếu số row theo status/reason, số interval mở và số interval đóng; lần chạy lại không được tạo thêm presence interval.
* Trước production migration phải backup đầy đủ `learning_sessions` và `session_presence`, đồng thời ghi lại migration version/checksum.
* Rollback là restore hai bảng từ cùng một backup nhất quán và gỡ Flyway schema-history entry tương ứng theo runbook vận hành; không rollback bằng cách suy ngược status/reason vì migration legacy là lossy.

## 14. Implementation plan đã chốt

### Phase 1 — Domain, schema và migration

* Mở rộng status/reason và lifecycle field của `LearningSession`.
* Tạo `SessionPresence`, index/constraint, repository lock/query và configuration binding.
* Viết migration schema/data idempotent, dry-run report và rollback plan.
* Gate: migration test và repository integration test thành công.

### Phase 2 — Lifecycle vertical slice

* Implement join, leave, reconnect, overlap và finalization dưới write lock.
* Slice bắt buộc: cả hai join → `IN_PROGRESS` → một người leave → rejoin đúng hạn → overlap chính xác.
* Bổ sung cả hai leave, late rejoin, duplicate event và concurrent finalization.
* Gate: lifecycle unit/integration test thành công.

### Phase 3 — Atomic matchmaking

* Thay queue hiện tại bằng entry snapshot có cấu trúc và critical section xác định.
* Implement selection/tie-breaker, transactional active-session check và after-commit notification.
* Test concurrent join, duplicate queue/active session và persist-failure recovery.

### Phase 4 — Scheduler và recovery

* Finalize match timeout, reconnect timeout và maximum duration.
* Khôi phục active session/deadline sau restart, không tạo presence timestamp giả.

### Phase 5 — API, security và Agora

* Áp dụng `spec_agora_token.md`, DTO, CSRF và exception mapping `404/403/409`.
* Không nhận identity, channel hoặc UID từ client.
* Test token/deadline đồng thời với finalizer.

### Phase 6 — Frontend và end-to-end verification

* Đồng bộ frontend theo session state; chỉ report join sau khi Agora join thành công.
* Hỗ trợ reconnect và API recovery khi mất WebSocket notification.
* Chạy full test matrix, `test`, `build` và manual flow bằng hai browser session.

Không bắt đầu phase sau khi gate của phase trước chưa đạt. Rating, group call và invitation không thuộc plan này.

## 15. Definition of Done

* Migration schema và data chạy an toàn.
* Có rollback plan cho migration.
* Test matrix được tự động hóa ở mức unit/integration phù hợp.
* `.\\gradlew.bat test` thành công.
* `.\\gradlew.bat build` thành công.
* API, Agora và frontend specs đồng bộ với implementation.
* Không còn lựa chọn thiết kế mở trong phạm vi Lifecycle V2.
