# Spec: User & Authentication MVP (Only Google, No Phone)

## 1. Trạng thái

- Trạng thái: **APPROVED - được duyệt ngày 2026-08-11, UPDATED ngày 2026-08-17**.
- Nhánh triển khai: `codex/feature-user-authentication`.
- Phạm vi MVP:
  - Loại bỏ hoàn toàn đăng ký và đăng nhập bằng số điện thoại/mật khẩu/OTP.
  - Sử dụng duy nhất phương thức đăng nhập và đăng ký tự động qua tài khoản Google (OAuth2 / OIDC).
  - Phân quyền `USER`/`ADMIN`, xem và cập nhật hồ sơ, level và avatar.
- Package hiện tại được giữ nguyên: `com.example.videocall_marching_language`.

## 2. Mục tiêu

Hệ thống xác định được user hiện tại từ SecurityContext (được lưu sau khi xác thực thành công qua Google OAuth2) phục vụ cho các tính năng match cặp, video call, rating và buddy.

Luồng duy nhất:
`Đăng nhập bằng Google -> Xem/cập nhật hồ sơ -> Đăng xuất`

## 3. Kiến trúc

Áp dụng Spring Boot MVC2 và constructor injection:

`Repository -> Service -> ServiceImpl -> Controller -> Thymeleaf`

Quy tắc:
- Controller không trả `User` entity trực tiếp.
- Dùng DTO cho dữ liệu view và form update profile.
- Business logic nằm trong service, không đặt trong controller.
- Authentication dùng Spring Security OAuth2 Client.
- CSRF được giữ bật cho các form thay đổi dữ liệu.
- Không dùng field injection.

## 4. Dependency

Thêm các dependency cần thiết vào `build.gradle`:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
```

Không thêm JWT hoặc SDK authentication ngoài trong vòng này.

## 5. Domain model

### 5.1. User

Hoàn thiện entity `User` với các field:

- `Long id`
- `String username`: tên hiển thị, bắt buộc, 2-50 ký tự (lấy từ Full Name của Google hoặc tiền tố email).
- `String email`: bắt buộc, duy nhất, lấy từ Google.
- `JapaneseLevel currentLevel`: `N5`, `N4`, `N3`, `N2`, `N1`.
- `float trustScore`: mặc định `0.0`.
- `String avatarUrl`: nullable, lưu URL ảnh đại diện (lấy từ Google hoặc Cloudinary khi cập nhật).
- `UserRole role`: `USER` hoặc `ADMIN`, mặc định `USER`.
- `UserStatus status`: `ACTIVE` hoặc `DISABLED`, mặc định `ACTIVE`.
- `LocalDateTime createdAt`
- `LocalDateTime updatedAt`

*Lưu ý: Xóa hoàn toàn các trường `phoneNumber`, `passwordHash`, và `isPhoneVerified` khỏi thực thể `User`.*

Enum đặt trong package `enums`, dùng `@Enumerated(EnumType.STRING)`.

### 5.2. Social Account

Thực thể `SocialAccount` dùng để lưu liên kết tài khoản Google:
- `Long id`
- `User user`: liên kết Many-to-One với `User`.
- `String provider`: "GOOGLE".
- `String providerId`: lưu claim `sub` của Google.

## 6. Repository

Tạo `UserRepository extends JpaRepository<User, Long>`:

```java
Optional<User> findByEmail(String email);
boolean existsByEmail(String email);
```

Tạo `SocialAccountRepository extends JpaRepository<SocialAccount, Long>`:

```java
Optional<SocialAccount> findByProviderAndProviderId(String provider, String providerId);
```

*Lưu ý: Xóa các phương thức tìm kiếm theo số điện thoại.*

## 7. DTO

### UserProfileResponse

- `id`
- `username`
- `email`
- `currentLevel`
- `trustScore`
- `avatarUrl`
- `role`

### UpdateProfileRequest

- `username`
- `currentLevel`
- `MultipartFile avatar` (optional)

Không cho phép user tự cập nhật email, role, status hoặc trustScore.

## 8. Service

### UserService

Các method chính:

```java
UserProfileResponse getCurrentProfile(String email);
UserProfileResponse updateCurrentProfile(String email, UpdateProfileRequest request);
```

*Xóa phương thức `register(RegisterRequest)` cũ.*

### GoogleOidcUserService

- Kế thừa `OAuth2UserService<OidcUserRequest, OidcUser>`.
- Load thông tin Google user qua Oidc.
- Tự động tạo `User` mới và `SocialAccount` tương ứng nếu chưa tồn tại.
- Nếu tài khoản có status là `DISABLED`, từ chối đăng nhập (ném ra `OAuth2AuthenticationException`).

### CustomUserDetailsService (Xóa)
- Do không sử dụng form login bằng password nữa, toàn bộ lớp này sẽ được xóa bỏ khỏi hệ thống.

### Avatar upload

- Tạo service riêng bọc Cloudinary, không gọi Cloudinary trực tiếp trong controller.
- Chấp nhận JPEG, PNG hoặc WebP.
- Kích thước tối đa 5 MB.
- Nếu upload thất bại, giữ nguyên avatar hiện tại.

## 9. Security configuration

Tạo `SecurityConfig` dùng `SecurityFilterChain`:

- Public:
  - `GET /login`
  - `/oauth2/**`, `/login/oauth2/**`
  - `/css/**`, `/js/**`, ảnh/static assets
- Cần đăng nhập:
  - `/profile/**`
  - `/video-call/**`
  - `/api/agora/**`
- Chỉ ADMIN:
  - `/admin/**`
- Google Login (OAuth2 Client):
  - Trang đăng nhập mặc định: `/login`
  - Cấu hình OAuth2 Login liên kết tới `GoogleOidcUserService`.
  - Redirect sau khi đăng nhập thành công: `/profile`.
- Logout:
  - Chỉ `POST /logout`
  - Invalidate HTTP session, xóa authentication và xóa cookie `JSESSIONID`.
  - Redirect về `/login?logout`.

Không disable CSRF toàn cục.

## 10. Controller và view

### AuthController

- `GET /login`: hiển thị trang đăng nhập chỉ chứa nút "Đăng nhập bằng Google".
- *Xóa hoàn toàn các route `/register` (cả GET và POST).*

### ProfileController

- `GET /profile`: hiển thị hồ sơ user hiện tại (lấy email từ SecurityContext).
- `GET /profile/edit`: hiển thị form cập nhật.
- `POST /profile/edit`: cập nhật username, level và avatar.

Luôn lấy identity từ `Authentication`/`Principal`, không nhận `userId` từ client.

### Thymeleaf templates

- `templates/auth/login.html` (chỉ chứa duy nhất nút Đăng nhập bằng Google).
- *Xóa template `templates/auth/register.html`.*
- `templates/users/profile.html`
- `templates/users/profile_edit.html`

## 11. Error handling

Tối thiểu cần các lỗi nghiệp vụ:

- `UserNotFoundException`
- `InvalidAvatarException`
- `AvatarUploadException`

*Loại bỏ `DuplicatePhoneNumberException` do không dùng số điện thoại đăng ký nữa.*

## 12. Transaction và concurrency

- Việc tự động đăng ký trong `GoogleOidcUserService` chạy trong transaction.
- Database unique constraint trên `email` là lớp bảo vệ cuối cùng tránh tạo trùng User khi có nhiều request đồng thời.
- `updateCurrentProfile` chạy trong transaction.

## 13. Test cases

### UserService unit test

- Cập nhật username và level của đúng user hiện tại qua email.
- Giữ avatar cũ nếu không upload file.
- Không lưu URL mới nếu Cloudinary upload lỗi.

### GoogleOidcUserService unit test

- Đăng nhập Google lần đầu: Tạo `User` mới với `email` và `username` từ Google, lưu `SocialAccount`.
- Đăng nhập Google các lần tiếp theo: Trả về tài khoản hiện tại mà không tạo mới.
- Từ chối đăng nhập nếu tài khoản có status `DISABLED`.

### Security/controller test

- Guest truy cập `/profile` bị chuyển đến `/login`.
- User đăng nhập thành công bằng tài khoản Google.
- User role `USER` không truy cập được `/admin/**`.
- Logout bằng POST làm session không còn authenticated.
- Form POST thiếu/không đúng CSRF bị từ chối.

### Verification

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

## 14. Acceptance criteria

- User đăng nhập/đăng ký được bằng tài khoản Google duy nhất.
- Không còn form đăng ký hay đăng nhập truyền thống, không còn trường số điện thoại và password.
- Guest không truy cập được profile, video call hoặc API Agora.
- User xem và cập nhật được username, level (N5-N1) và avatar.
- Toàn bộ test và build chạy thành công.

## 15. Ngoài phạm vi và task kế tiếp

Ngoài phạm vi:
- Đăng nhập bằng Facebook hoặc các mạng xã hội khác ngoài Google.
- OTP hay số điện thoại.
- Reset/change password.

Task kế tiếp sau feature này:
- Tag và lựa chọn level/topic trước matching.
- LearningSession và matchmaking.
- Ràng Agora token với authenticated user và session.
