# Spec: Matchmaking & LearningSession Lifecycle V2

## 1. Trạng thái

- **DRAFT — chờ review trước khi code**.
- Baseline: nhánh `feature/learning-session`, kiểm tra ngày 2026-08-25.
- Phạm vi: queue/matching, lifecycle, presence/reconnect và completion.
- Không triển khai rating trong spec này.

## 2. Mục tiêu và phạm vi

`Authenticated user -> queue -> atomic 1-1 match -> LearningSession -> both join -> track overlap/reconnect -> finalize`

Trong phạm vi: queue in-memory một instance, level/tag matching, persistent session,
participant authorization, timeout/finalization và history.

Ngoài phạm vi: Redis/multi-instance, group/buddy, recording, rating và thay đổi auth.

## 3. MatchingQueueEntry

`WAITING` thuộc queue entry, không thuộc `LearningSession`.

Entry chứa `userId`, level/tag snapshot, `enqueuedAt` và connection reference.

- Một user có tối đa một queue entry.
- User có active session không được vào queue.
- Cancel/retry/disconnect idempotent.
- Compound match operation atomic trong phạm vi process.

## 4. LearningSession

- Chỉ tạo sau khi match; hai participant khác nhau.
- `channelName` unique, backend sinh, không chứa PII trực tiếp.
- Lưu level/tag snapshot và `matchedAt`.

```text
MATCHED
   |-- both joined ----------------> IN_PROGRESS
   |-- cancelled/match timeout ----> CANCELLED

IN_PROGRESS
   |-- valid end + overlap >= 300s -> COMPLETED
   |-- end/error + overlap < 300s --> INCOMPLETE
```

Terminal state không chuyển ngược về active.

Completion reason mục tiêu:

- `BOTH_LEFT`
- `ONE_LEFT_TIMEOUT`
- `MAX_DURATION_REACHED`
- `INSUFFICIENT_DURATION`
- `TECHNICAL_FAILURE`
- `MATCH_TIMEOUT`
- `CANCELLED_BY_USER`
- `CANCELLED_BY_SYSTEM`

Không tiếp tục dùng reason mơ hồ `NORMAL` hoặc `ERROR`.

## 5. Cấu hình

```properties
learning-session.minimum-overlap-seconds=300
learning-session.reconnect-grace-seconds=60
learning-session.maximum-duration-seconds=3600
matching.adjacent-level-after-seconds=120
matching.match-timeout-seconds=600
```

Tên property có thể đổi theo convention khi implement; default chỉ đổi sau review.

## 6. Matching rules

1. Identity/level lấy từ authenticated user, không tin payload.
2. Ưu tiên compatible tag cùng level.
3. Sau 120 giây cho level liền kề theo N5-N1.
4. Không self-match hoặc match user có active session.
5. Kiểm tra lại conflict trong transaction trước persist.
6. Persist fail phải rollback hoặc enqueue lại có kiểm soát.
7. Chỉ publish `MATCHED` sau commit.

## 7. Presence và overlap

### Join

- Chỉ participant, session `MATCHED` hoặc `IN_PROGRESS`.
- Join lặp khi đang present là no-op.
- Cả hai present lần đầu: `MATCHED -> IN_PROGRESS`, đặt `startedAt`.

### Leave/reconnect

- Leave lặp khi đã absent là no-op.
- Một user leave: đóng overlap đang mở và bắt đầu grace 60 giây.
- Rejoin trong grace mở khoảng mới, không kết thúc session.
- Hết grace: finalizer kết thúc bằng `ONE_LEFT_TIMEOUT`.
- WebSocket disconnect chỉ là presence signal, không kết thúc ngay.

### Persistence

Khuyến nghị `SessionPresence(sessionId, userId, joinedAt, leftAt)` để audit nhiều lần
reconnect. Aggregate tối giản chỉ được dùng nếu persist đủ current presence, accumulated
overlap và grace deadline để phục hồi sau restart.

Tổng overlap phải đúng qua nhiều chu kỳ; mutation dùng transaction/write lock.

## 8. Finalization

- `COMPLETED`: từng `IN_PROGRESS`, overlap >= 300 giây, kết thúc hợp lệ.
- `INCOMPLETE`: từng `IN_PROGRESS`, overlap < 300 giây hoặc technical failure.
- `CANCELLED`: chưa bắt đầu và bị cancel/match timeout/system cancel.
- Max duration 3600 giây: `MAX_DURATION_REACHED`.
- Scheduler, leave và disconnect chạy đồng thời chỉ finalize một lần.
- Terminal state lưu `endedAt`, reason và overlap phù hợp.

## 9. API/WebSocket

- STOMP join/cancel không dùng client identity để authorization.
- `GET /api/sessions/{id}`: participant-only.
- `GET /api/sessions/{id}/token`: theo `spec_agora_token.md`.
- POST `join-agora`/`leave-agora`: participant, CSRF, idempotent.
- Controller trả DTO.
- Session không tồn tại là `404`; authenticated non-participant mục tiêu là `403`.

## 10. Concurrency và recovery

- Queue thread-safe; compound match có critical section rõ.
- Active-session query và lifecycle mutation có transaction/lock.
- Không swallow exception; log context nhưng không log token/secret.
- WebSocket notify fail không rollback session đã commit; client có recovery/poll.
- Restart phải tiếp tục finalize từ persisted deadline/state.

## 11. Test matrix

### Matching

- Same level/tag match; adjacent level chỉ sau timeout.
- Không self-match, duplicate queue hoặc duplicate active session.
- Concurrent request không ghép một user hai lần.
- Persist fail không làm mất entry không thể phục hồi.

### Authorization

- Participant A/B truy cập được; user C nhận `403`.
- Client không override identity/channel/UID.

### Lifecycle

- Chỉ cả hai join mới vào `IN_PROGRESS`.
- Duplicate join/leave là no-op.
- Nhiều reconnect tính đúng overlap.
- Reconnect trong 60 giây giữ session; quá grace finalize đúng một lần.
- Dưới 300 giây là `INCOMPLETE`; từ 300 giây là `COMPLETED`.
- Max duration tự finalize; terminal state không reopen.

### Security/integration

- POST thiếu/sai CSRF bị từ chối.
- History trả đúng peer cho cả user1/user2.
- Full flow qua hai authenticated browser sessions.

## 12. Migration từ code hiện tại

- `ENDED` -> terminal state mục tiêu.
- `NORMAL/PEER_LEFT/TIMEOUT/ERROR` -> reason cụ thể.
- Mapping dữ liệu hiện có phải review trước migration.
- Timestamp presence đơn lẻ -> mô hình presence được chọn.
- Disconnect kết thúc ngay -> persisted grace deadline/finalizer.
- Giữ session token và participant authorization, harden status/error.

## 13. Definition of Done

- Reviewer duyệt spec trước khi code.
- Migration an toàn cho dữ liệu hiện có.
- Test matrix được tự động hóa ở mức phù hợp.
- `.\gradlew.bat test` và `.\gradlew.bat build` thành công.
- API/frontend specs đồng bộ implementation.
