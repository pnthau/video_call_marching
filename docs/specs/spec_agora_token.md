# Spec: Session-based Agora RTC Token

## 1. Trạng thái

- **IMPLEMENTED — Giai đoạn 1 nghiệm thu PASS ngày 2026-08-30**.
- Spec này thay API cũ nhận `channelName` và `uid` trực tiếp từ client.

## 2. Quy tắc

- Client chỉ yêu cầu token bằng `sessionId`.
- Current user lấy từ Spring Security `Authentication`.
- Chỉ participant của session `MATCHED` hoặc `IN_PROGRESS` được cấp token.
- Backend lấy `channelName` từ database và suy ra Agora UID từ participant.
- Client không được truyền hoặc override `channelName`, Agora UID hoặc identity.
- Không cấp token cho `COMPLETED`, `INCOMPLETE` hoặc `CANCELLED`.
- Không lưu token; token sử dụng `ROLE_PUBLISHER`, TTL mặc định 3600 giây.
- Token được phép refresh khi session vẫn active; TTL tính từ lần cấp token và không đại diện cho maximum session duration.
- Cấu hình Agora đọc từ configuration hiện có.
- Backend tiếp tục quản lý channel/UID khi có group call hoặc invitation trong tương lai. Client chỉ gửi ý định nghiệp vụ; backend xác thực membership trước khi cấp token.

## 3. API

```http
GET /api/sessions/{sessionId}/token
Cookie: JSESSIONID=...
```

```json
{
  "token": "<rtc-token>",
  "channelName": "<backend-generated-channel>",
  "uid": 123
}
```

Không hỗ trợ endpoint cũ:

```http
GET /api/agora/token?channelName=...&uid=...
```

## 4. Authorization và HTTP contract

`JSESSIONID -> current User -> LearningSession -> participant check -> status/deadline check -> DB channelName -> backend UID -> token`

- Chưa authentication: `401 Unauthorized` hoặc redirect theo Spring Security entry point.
- Session không tồn tại: `404 Not Found`.
- Authenticated non-participant: `403 Forbidden`.
- Session `COMPLETED`, `INCOMPLETE` hoặc `CANCELLED`: `409 Conflict`.
- Session đã hết deadline hợp lệ: `409 Conflict`.
- Không che `403 Forbidden` thành `404 Not Found`.

Nếu deadline đã hết nhưng scheduler chưa finalize:

1. Service lấy write lock cho session.
2. Service finalize session an toàn.
3. Không sinh token.
4. Trả `409 Conflict`.

## 5. Thiết kế

- `AgoraTokenService` chỉ sinh token từ dữ liệu backend đã validate và không tự participant authorization.
- `LearningSessionService` chịu trách nhiệm current user, tìm session, participant authorization, status/deadline, channel và UID.
- Controller trả DTO, không trả entity.
- Status check và dữ liệu sinh token phải được bảo vệ khỏi concurrent finalization.
- Không cấp token đồng thời với việc session chuyển sang terminal state.
- Token, app certificate và secret không được ghi database hoặc log.

### 5.1. Agora UID

- Không ép `Long userId` sang `int` nếu chưa kiểm tra miền giá trị.
- Mỗi participant có Agora UID dương, ổn định trong phạm vi session và unique trong channel.
- Implementation ưu tiên lưu UID snapshot cho từng participant trên session hoặc bảng membership; backend sinh và kiểm tra collision khi tạo session.
- Client không được chọn UID, kể cả với group call/invitation tương lai.

## 6. Acceptance criteria

- Participant A/B của active session lấy được token.
- User C nhận `403`; missing session nhận `404`; terminal/expired session nhận `409`.
- Client không override channel/UID; token dùng đúng channel và UID của session membership.
- Active participant refresh được token khi token cũ gần hoặc đã hết TTL.
- Token không được ghi database hoặc log.
- Test bao phủ authorization, status, deadline, UID range/collision, refresh và concurrent finalization.

## 7. Implementation đã nghiệm thu

1. Thêm UID snapshot/membership mapping an toàn và migration nếu cần.
2. Tách validation/authorization vào `LearningSessionService`; giữ `AgoraTokenService` thuần token generation.
3. Thay controller flow bằng một service operation atomic trả dữ liệu token DTO.
4. Thêm exception mapping rõ `404/403/409`; xóa hoàn toàn endpoint/request param cũ.
5. Thêm test participant A/B/C, terminal, expired deadline, token refresh, UID range/collision và race với finalizer.
6. Đồng bộ frontend chỉ gửi `sessionId`; không nhận input channel/UID từ người dùng.

Phần này đã được triển khai cùng Phase 5 của `spec_learning_session_matchmaking_v2.md`.

## 8. Verification

- Participant A/B lấy token từ cùng session backend-generated.
- User C nhận `403`; missing session nhận `404`; terminal/expired session nhận `409`.
- Reconnect trong grace lấy token mới cho cùng session/channel.
- Endpoint legacy nhận `channelName`/`uid` không còn hoạt động.
- Token, certificate và secret không xuất hiện trong database hoặc application log.

```powershell
.\gradlew.bat test
.\gradlew.bat build
```
