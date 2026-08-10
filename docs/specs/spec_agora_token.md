# overview (mục tiêu)
- Tích hợp dịch vụ sinh Agora RTC Token cho ứng dụng `videocall_marching_language`.
- Giúp Frontend lấy được RTC Token tạm thời để tham gia phòng gọi Video 1-on-1 an toàn.

# rules (quy tắc nghiệp vụ)
- Không lưu Token vào Database (Token sinh trực tiếp trên RAM và trả về API).
- Thời gian sống mặc định của Token là 3600 giây (1 tiếng).
- Token sử dụng vai trò `ROLE_PUBLISHER` (cho phép gửi và nhận luồng Video/Audio).
- Cấu hình `agora.app-id` và `agora.app-certificate` được đọc từ `application.properties`.

# technical design (thiết kế kỹ thuật)
1. **Dependency**:
   - `io.agora:authentication:2.1.3` (Đã thêm vào `build.gradle`).
2. **Package**:
   - `com.example.videocall_marching_language.service`
   - `com.example.videocall_marching_language.controller`
3. **Service (`AgoraTokenService`)**:
   - Khai báo `@Service`.
   - Tiêm `@Value("${agora.app-id}")` và `@Value("${agora.app-certificate}")`.
   - Method `generateToken(String channelName, int uid)` sử dụng `RtcTokenBuilder2` để sinh ra chuỗi Token.
4. **Controller (`AgoraController`)**:
   - Khai báo `@RestController`, `@RequestMapping("/api/agora")`.
   - Sử dụng Constructor Injection với `@RequiredArgsConstructor` để tiêm `AgoraTokenService`.
   - API Endpoint: `GET /api/agora/token`
   - Request Params: `channelName` (String), `uid` (int).
   - Response: Chuỗi String chứa RTC Token.

# acceptance criteria (tiêu chí nghiệm thu)
- API `GET /api/agora/token?channelName=room1&uid=123` trả về chuỗi Token mã hóa hợp lệ của Agora.
- Dự án build thành công không có lỗi compile.

# verification (kiểm tra)
- **Bước 1**: Tạo file `AgoraTokenService.java`.
- **Bước 2**: Tạo file `AgoraController.java`.
- **Bước 3**: Chạy `./gradlew classes` để kiểm tra biên dịch.
