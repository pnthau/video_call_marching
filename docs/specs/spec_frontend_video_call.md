# overview (mục tiêu)
- Tạo giao diện Web (Frontend) cơ bản để 2 học viên có thể thực hiện Video Call 1-on-1 bằng Agora RTC.
- Cho phép người dùng nhập Channel Name, User ID để lấy Token từ Backend và kết nối phòng gọi.

# rules (quy tắc nghiệp vụ)
- Sử dụng Agora Web SDK (phiên bản 4.x) thông qua CDN.
- Gọi API Backend `GET /api/agora/token` để lấy RTC token động, tuyệt đối không hardcode token trên JS.
- Giao diện tối giản gồm 2 màn hình video: Local (của bản thân) và Remote (của đối tác).
- Hỗ trợ các nút điều khiển cơ bản: Join (Tham gia), Leave (Rời phòng), Toggle Mic (Bật/Tắt Mic), Toggle Camera (Bật/Tắt Camera).

# technical design (thiết kế kĩ thuật)
1. **Frontend Stack**: Sử dụng boostrap local, JavaScript (Vanilla), đặt tĩnh trong thư mục `src/main/resources/static/video_call`.
2. **Cấu trúc file**:
   - `src/main/resources/static/video_call.html`: Chứa giao diện.
   - (**Lưu ý : ưu tiên sữ dụng boostrap) `src/main/resources/static/css/style.css`:  Chứa style cho khung video .
   - (**Lưu ý : ưu tiên sữ dụng boostrap)`src/main/resources/static/js/video_call.js`: Chứa logic tương tác với Agora .
3. **Giao diện (`video_call.html`)**:
   - Thêm Agora SDK: `<script src="https://download.agora.io/sdk/release/AgoraRTC_N.js"></script>`
   - Form nhập: Input cho `Channel Name` và `UID`.
   - Vùng hiển thị video: `<div id="local-player"></div>` và `<div id="remote-player"></div>`.
4. **Logic (`video_call.js`)**:
   - Khởi tạo client: `const client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });`
   - Gọi API: Dùng `fetch()` gọi tới `/api/agora/token?channelName=...&uid=...`
   - Tham gia phòng: `await client.join(appId, channelName, token, uid);`
   - Bật Mic/Cam: `const [localAudioTrack, localVideoTrack] = await AgoraRTC.createMicrophoneAndCameraTracks();`
   - Lắng nghe event `client.on("user-published", async (user, mediaType) => {...})` để hiển thị luồng video của người kia.

# acceptance criteria (tiêu chí nghiệm thu)
- Mở `http://localhost:8080/video-call` trên trình duyệt.
- Mở Tab 1 (Nhập Channel: "room1", UID: 1) -> Tham gia thành công, thấy hình camera bản thân.
- Mở Tab 2 (Nhập Channel: "room1", UID: 2) -> Tham gia thành công, 2 tab thấy hình và nghe được tiếng của nhau.

# verification (kiểm tra)
- **Bước 1**: Tạo file `video_call.html` trong thư mục `templates/users`, `style.css`, `video_call.js` trong thư mục `static/css` hoặc `static/js`.
- **Bước 2**: Khởi động lại Spring Boot app.
- **Bước 3**: Truy cập trình duyệt và test với 2 tab/cửa sổ khác nhau.
