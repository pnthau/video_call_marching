# Spec: Session-driven Frontend Video Call

## 1. Trạng thái

- **PARTIAL — đã gọi được Agora, còn thiếu CSRF/reconnect hardening**.
- UI do matchmaking và `sessionId` dẫn dắt; user không nhập channel/UID.

## 2. Quy tắc

# rules (quy tắc nghiệp vụ)
- Sử dụng Agora Web SDK (phiên bản 4.x) thông qua CDN.
- Gọi API Backend `GET /api/agora/token` để lấy RTC token động, tuyệt đối không hardcode token trên JS.
- Giao diện tối giản gồm 2 màn hình video: Local (của bản thân) và Remote (của đối tác).
- Hỗ trợ các nút điều khiển cơ bản: Join (Tham gia), Leave (Rời phòng), Toggle Mic (Bật/Tắt Mic), Toggle Camera (Bật/Tắt Camera).

## 3. UI state

```text
SETUP -> WAITING -> JOINING -> IN_CALL -> RECONNECTING | ENDED
```

- `WAITING`: queue, cho phép cancel.
- `JOINING`: lấy token, mở thiết bị, join Agora.
- `IN_CALL`: local/remote video, mic/camera.
- `RECONNECTING`: mất kết nối trong grace 60 giây.
- `ENDED`: kết quả session và điều hướng rating nếu đủ điều kiện.

## 4. Luồng

1. User chọn tag và vào queue qua STOMP.
2. Backend lấy identity/level từ authenticated principal.
3. Khi `MATCHED`, client nhận `sessionId` và peer display data.
4. Client lấy token, join Agora, publish track khả dụng.
5. Client report join kèm CSRF.
6. Leave/end gửi event idempotent trước hoặc cùng cleanup local.
7. Disconnect chuyển `RECONNECTING`, không tự kết thúc trước khi backend hết grace.

## 5. Error handling

- Token fail: không join, hiển thị lỗi retry.
- Thiếu mic/camera: dùng thiết bị còn khả dụng.
- Backend report join fail sau Agora join: leave Agora và báo lỗi.
- Không reset `currentSessionId` trước khi leave event được gửi an toàn.
- `beforeunload` chỉ best-effort; backend dùng persisted state/finalizer.

## 6. CSRF/WebSocket

- Template cung cấp CSRF header name/token.
- Helper JavaScript thêm header cho mọi POST.
- STOMP dùng principal của HTTP session; payload `userId` không có giá trị authorization.

## 7. Acceptance criteria

- Không có input channel/UID.
- Chỉ participant join đúng session.
- Hai browser session match và gọi được.
- POST join/leave không 403 vì thiếu CSRF.
- Retry không cộng sai overlap.
- Reconnect trong 60 giây giữ session; quá grace thì backend finalize.
- Refresh/disconnect không làm UI/backend lệch vĩnh viễn.

## 8. Verification

- Security/controller test cho CSRF và authorization.
- Manual test bằng hai browser profile/tài khoản Google khác nhau.
- `.\gradlew.bat test` và `.\gradlew.bat build`.
