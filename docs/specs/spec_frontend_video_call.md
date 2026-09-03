# Spec: Authenticated Session Video Call Frontend

## 1. Trạng thái

- **IMPLEMENTED — Giai đoạn 1 nghiệm thu PASS ngày 2026-08-30**.
- Frontend dùng `LearningSession` do backend tạo; không nhận hoặc hardcode identity, channel hay Agora UID.

## 2. Phạm vi

- Hai authenticated user chọn level, topic và activity để matchmaking 1-1.
- Nhận `sessionId` từ backend/WebSocket rồi lấy Agora token theo session.
- Hiển thị local/remote media và điều khiển mic/camera độc lập.
- Khôi phục active session sau reload hoặc WebSocket reconnect.
- Hiển thị trạng thái peer reconnecting/recovered và terminal notification.

Không thuộc phạm vi: group call, Buddy, recording, Redis/distributed matching và Peer Rating.

## 3. Contract backend

Frontend chỉ gọi token bằng:

```http
GET /api/sessions/{sessionId}/token
```

Frontend báo lifecycle bằng các endpoint participant-only có CSRF:

```http
POST /api/sessions/{sessionId}/join-agora
POST /api/sessions/{sessionId}/leave-agora
```

Khi page load, frontend resolve active session từ backend. `sessionId` phía client chỉ là hint; backend xác minh authenticated participant, status và reconnect deadline. `localStorage` không phải source of truth cho authorization.

HTTP contract:

- Non-participant: `403`.
- Missing session: `404`.
- Terminal/expired token hoặc join: `409`.
- Payload không hợp lệ: `400`.

Endpoint legacy `/api/agora/token?channelName=...&uid=...` không được hỗ trợ.

## 4. Matchmaking và recovery states

- `WAITING`: user có đúng một queue entry.
- `MATCHED`: backend trả cùng `sessionId` cho hai participant.
- `RECOVERING` / `RECONNECTING`: frontend đang resolve và join lại active session.
- `PEER_RECONNECTING`: peer tạm rời nhưng còn trong grace period.
- `PEER_RECOVERED`: peer đã join lại cùng session/channel.
- `SESSION_ENDED`: backend đã finalize; UI rời call mà không cần reload.

Page load không enqueue tự động khi user còn session `MATCHED`/`IN_PROGRESS`. Reconnect trong grace dùng cùng session, token mới và presence interval mới. Sau deadline, backend finalize trước rồi trả `409`; terminal session không reopen và frontend chỉ cho matchmaking lại sau khi hiển thị kết quả kết thúc.

## 5. Agora/media

- Dùng Agora Web SDK 4.x.
- Backend trả `token`, `channelName`, `uid`; frontend không cho người dùng sửa các giá trị này.
- Chỉ báo `join-agora` sau khi Agora join thành công.
- Audio và video track được tạo độc lập để một thiết bị thiếu/lỗi không vô hiệu thiết bị còn lại.
- Refresh/leave đóng presence hiện tại; rejoin tạo interval mới, không cộng trùng overlap.

## 6. Cấu hình mặc định liên quan

```properties
learning-session.minimum-overlap-seconds=300
learning-session.reconnect-grace-seconds=60
learning-session.maximum-duration-seconds=3600
matching.adjacent-level-after-seconds=120
matching.match-timeout-seconds=600
```

## 7. Acceptance evidence

- Hai Google account độc lập match và nhận cùng session/channel.
- Chỉ khi cả hai join, session mới chuyển `IN_PROGRESS`.
- Local/remote media và mic/camera toggle hoạt động.
- Reload khôi phục cùng session, không tạo queue/session mới.
- Reconnect trong grace tạo presence interval mới; peer nhận reconnecting/recovered.
- Scheduler finalize sau deadline; peer nhận `SESSION_ENDED` và UI tự cập nhật.
- Terminal token/join trả `409`; session không reopen.
- Profile C bị từ chối truy cập session/token bằng `403`.

## 8. Verification

```powershell
$env:SPRING_FLYWAY_ENABLED='false'
.\gradlew.bat test
Remove-Item Env:SPRING_FLYWAY_ENABLED

$env:SPRING_FLYWAY_ENABLED='false'
.\gradlew.bat build
Remove-Item Env:SPRING_FLYWAY_ENABLED
```
