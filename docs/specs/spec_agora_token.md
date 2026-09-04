# Spec: Session-based Agora RTC Token

## 1. Trạng thái

- **IMPLEMENTED BASELINE — cần hardening cùng lifecycle v2**.
- Spec này thay API cũ nhận `channelName` và `uid` trực tiếp từ client.

## 2. Quy tắc

- Client chỉ yêu cầu token bằng `sessionId`.
- Current user lấy từ Spring Security `Authentication`.
- Chỉ participant của session `MATCHED` hoặc `IN_PROGRESS` được cấp token.
- Backend lấy `channelName` từ database và suy ra Agora UID từ participant.
- Không cấp token cho `COMPLETED`, `INCOMPLETE`, `CANCELLED`.
- Không lưu token; dùng `ROLE_PUBLISHER`, TTL mặc định 3600 giây.
- Cấu hình Agora đọc từ cấu hình hiện có.

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

Không hỗ trợ `GET /api/agora/token?channelName=...&uid=...`.

## 4. Authorization

`JSESSIONID -> current User -> LearningSession -> participant check -> status check -> DB channelName -> backend UID -> token`

- Không tồn tại: `404`.
- Authenticated non-participant: mục tiêu `403`.
- Terminal/hết hạn: `409` hoặc `410`, chốt khi implement.
- Không che forbidden thành not-found nếu chưa có policy security được duyệt.

## 5. Thiết kế

- `AgoraTokenService` chỉ sinh token từ dữ liệu backend đã validate.
- `LearningSessionService` chịu trách nhiệm authorization/session validity.
- Controller trả DTO, không trả entity.
- Trạng thái phải được kiểm tra an toàn khi có finalization đồng thời.

## 6. Acceptance criteria

- Participant A/B lấy được token; user C không lấy được.
- Client không override channel/UID.
- Token dùng channel của đúng session.
- Terminal session không được cấp token mới.
- Token không được ghi database/log.
- Test bao phủ participant và status validation.

## 7. Verification

```powershell
.\gradlew.bat test
.\gradlew.bat build
```
