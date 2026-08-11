# Spec: User & Authentication MVP

## 1. Trạng thái

- Trạng thái: **APPROVED - được duyệt ngày 2026-08-11**.
- Nhánh triển khai: `codex/feature-user-authentication`.
- Phạm vi MVP: đăng ký/đăng nhập bằng số điện thoại và password, đăng xuất,
  phân quyền `USER`/`ADMIN`, xem và cập nhật hồ sơ, level và avatar.
- Google OAuth, Facebook và OTP verification chưa triển khai trong vòng này.
- Package hiện tại được giữ nguyên: `com.example.videocall_marching_language`.

## 2. Mục tiêu

Sau feature này, hệ thống phải xác định được user hiện tại để các feature matching,
video call, rating, buddy và nhiệm vụ không còn nhận `userId` tùy ý từ client.

Luồng chính:

`Đăng ký -> đăng nhập -> xem/cập nhật hồ sơ -> đăng xuất`

## 3. Kiến trúc

Áp dụng Spring Boot MVC2 và constructor injection:

`Repository -> Service -> ServiceImpl -> Controller -> Thymeleaf`

Quy tắc:

- Controller không trả `User` entity trực tiếp.
- Dùng DTO cho form input và dữ liệu view.
- Business logic nằm trong service, không đặt trong controller.
- Authentication dùng Spring Security và HTTP session.
- CSRF được giữ bật cho các form thay đổi dữ liệu.
- Không dùng field injection.

## 4. Dependency

Thêm đúng dependency cần thiết vào `build.gradle`:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-security'
```

Không thêm JWT, OAuth2 Client hoặc SDK authentication ngoài trong vòng này.

## 5. Domain model

### 5.1. User

Hoàn thiện entity `User` với các field:

- `Long id`
- `String username`: tên hiển thị, bắt buộc, 2-50 ký tự.
- `String phoneNumber`: bắt buộc trong MVP, duy nhất, đã chuẩn hóa.
- `String passwordHash`: bắt buộc, không bao giờ trả ra DTO.
- `JapaneseLevel currentLevel`: `N5`, `N4`, `N3`, `N2`, `N1`.
- `float trustScore`: mặc định `0.0`, chưa tính trong feature này.
- `String avatarUrl`: nullable, lưu URL Cloudinary.
- `Boolean isPhoneVerified`: mặc định `false`.
- `UserRole role`: `USER` hoặc `ADMIN`, mặc định `USER`.
- `UserStatus status`: `ACTIVE` hoặc `DISABLED`, mặc định `ACTIVE`.
- `LocalDateTime createdAt`
- `LocalDateTime updatedAt`

Enum đặt trong package `enums`, dùng `@Enumerated(EnumType.STRING)`.

### 5.2. Quy tắc số điện thoại

- Trim khoảng trắng và loại dấu cách, dấu chấm, dấu gạch ngang trong input.
- Chấp nhận định dạng Việt Nam `0xxxxxxxxx` hoặc `+84xxxxxxxxx`.
- Chuẩn hóa về dạng `+84xxxxxxxxx` trước khi kiểm tra unique và lưu.
- Sau chuẩn hóa phải có mã quốc gia `+84` và 9 chữ số thuê bao.
- Không hiển thị thông báo khác nhau giữa “sai password” và “không tồn tại tài khoản”
  trong trang đăng nhập.

### 5.3. Quy tắc password

- Input từ 8 đến 72 ký tự.
- Có ít nhất một chữ cái và một chữ số.
- `confirmPassword` phải trùng password khi đăng ký.
- Lưu bằng `PasswordEncoder` (BCrypt), tuyệt đối không lưu plain text.
- Không log password, confirmPassword hoặc password hash.

## 6. Repository

Tạo `UserRepository extends JpaRepository<User, Long>`:

```java
Optional<User> findByPhoneNumber(String phoneNumber);
boolean existsByPhoneNumber(String phoneNumber);
```

Không tạo repository cho `SocialAccount` trong vòng MVP này.

## 7. DTO

### RegisterRequest

- `username`
- `phoneNumber`
- `password`
- `confirmPassword`
- `currentLevel`

### LoginRequest

Spring Security form login sử dụng:

- `phoneNumber`
- `password`

DTO này chỉ tạo nếu controller custom cần bind/validate; không bắt buộc lưu thành object
nếu Spring Security xử lý trực tiếp form login.

### UserProfileResponse

- `id`
- `username`
- `phoneNumberMasked`
- `currentLevel`
- `trustScore`
- `avatarUrl`
- `isPhoneVerified`
- `role`

Không chứa `passwordHash`.

### UpdateProfileRequest

- `username`
- `currentLevel`
- `MultipartFile avatar` (optional)

Không cho phép user tự cập nhật role, status, trustScore hoặc phone verification.

## 8. Service

### UserService

Các method chính:

```java
UserProfileResponse register(RegisterRequest request);
UserProfileResponse getCurrentProfile(String phoneNumber);
UserProfileResponse updateCurrentProfile(String phoneNumber, UpdateProfileRequest request);
```

### CustomUserDetailsService

- Load user theo phone number đã chuẩn hóa.
- User `DISABLED` không được đăng nhập.
- Map `UserRole` thành authority `ROLE_USER` hoặc `ROLE_ADMIN`.

### Avatar upload

- Tạo service riêng bọc Cloudinary, không gọi Cloudinary trực tiếp trong controller.
- Chấp nhận JPEG, PNG hoặc WebP.
- Kích thước tối đa 5 MB.
- Nếu upload thất bại, không cập nhật `avatarUrl` trong database.
- Nếu request không có file avatar, giữ nguyên avatar hiện tại.
- Xóa ảnh Cloudinary cũ nằm ngoài phạm vi MVP để tránh xóa nhầm tài nguyên.

## 9. Security configuration

Tạo `SecurityConfig` dùng `SecurityFilterChain`:

- Public:
  - `GET /login`
  - `GET /register`
  - `POST /register`
  - `/css/**`, `/js/**`, ảnh/static assets
- Cần đăng nhập:
  - `/profile/**`
  - `/video-call/**`
  - `/api/agora/**`
- Chỉ ADMIN:
  - `/admin/**`
- Form login:
  - Trang: `/login`
  - Username parameter: `phoneNumber`
  - Success URL: `/profile`
  - Failure URL: `/login?error`
- Logout:
  - Chỉ `POST /logout`
  - Invalidate HTTP session và xóa authentication.
  - Redirect `/login?logout`.

Không disable CSRF toàn cục. API Agora hiện dùng GET nên chưa cần CSRF, nhưng phải yêu cầu
authenticated user trong vòng này; việc ràng token vào `LearningSession` thuộc spec sau.

## 10. Controller và view

### AuthController

- `GET /register`: hiển thị form đăng ký.
- `POST /register`: validate, tạo user và redirect về `/login?registered`.
- `GET /login`: hiển thị trang đăng nhập.

Nếu phone đã tồn tại, trả form đăng ký với lỗi field-level; không tạo user mới.

### ProfileController

- `GET /profile`: hiển thị hồ sơ user hiện tại.
- `GET /profile/edit`: hiển thị form cập nhật.
- `POST /profile/edit`: cập nhật username, level và avatar.

Không nhận `userId` từ form/profile URL. Luôn lấy identity từ `Authentication`/`Principal`.

### Thymeleaf templates

- `templates/auth/login.html`
- `templates/auth/register.html`
- `templates/users/profile.html`
- `templates/users/profile_edit.html`

Yêu cầu UI:

- Dùng Bootstrap local đã có trong project.
- Hiển thị validation cạnh field tương ứng.
- Form logout dùng POST và có CSRF token do Thymeleaf/Spring Security sinh.
- Không hiển thị password cũ hoặc password hash.
- Avatar có ảnh mặc định khi `avatarUrl` null.

## 11. Error handling

Tối thiểu cần các lỗi nghiệp vụ:

- `DuplicatePhoneNumberException`
- `UserNotFoundException`
- `InvalidAvatarException`
- `AvatarUploadException`

Controller xử lý lỗi form có thể dùng `BindingResult`; lỗi dùng chung có thể được map qua
`@ControllerAdvice` nếu giúp tránh lặp code.

Không hiển thị stack trace hoặc lỗi Cloudinary chi tiết cho end user.

## 12. Transaction và concurrency

- `register` chạy trong transaction.
- Database unique constraint trên `phone_number` là lớp bảo vệ cuối cùng khi hai request
  đăng ký đồng thời.
- Service kiểm tra trùng để trả lỗi thân thiện, nhưng vẫn phải xử lý `DataIntegrityViolationException`.
- `updateCurrentProfile` chạy trong transaction.
- Upload avatar thực hiện trước khi gán URL mới; database chỉ lưu URL khi upload thành công.

## 13. Test cases

### UserService unit test

- Đăng ký hợp lệ tạo role `USER`, status `ACTIVE`, level đúng và password đã hash.
- Chuẩn hóa `0xxxxxxxxx` thành `+84xxxxxxxxx`.
- Từ chối phone sai định dạng.
- Từ chối phone đã tồn tại.
- Từ chối password yếu hoặc confirm không khớp.
- Không trả password hash trong response.
- Cập nhật username và level của đúng user hiện tại.
- Giữ avatar cũ nếu không upload file.
- Không lưu URL nếu Cloudinary upload lỗi.

### Security/controller test

- Guest truy cập `/profile` bị chuyển đến `/login`.
- User đăng nhập thành công bằng phone/password đúng.
- Sai credentials quay lại `/login?error` với thông báo chung.
- User role `USER` không truy cập được `/admin/**`.
- Logout bằng POST làm session không còn authenticated.
- Form POST thiếu/không đúng CSRF bị từ chối.
- User A không thể cập nhật profile User B bằng cách sửa request.

### Verification

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

## 14. Acceptance criteria

- User đăng ký được bằng username, phone, password và level.
- Phone được chuẩn hóa và không thể đăng ký trùng.
- Password được BCrypt hash trong database.
- User đăng nhập/đăng xuất bằng HTTP session.
- Có role `USER` và `ADMIN`; route admin bị bảo vệ.
- Guest không truy cập được profile, video call hoặc API Agora.
- User xem và cập nhật được username, N5-N1 và avatar Cloudinary.
- Controller không trả entity trực tiếp và không tin `userId` từ client.
- Validation hiển thị đúng trên form.
- Toàn bộ test và build chạy thành công.

## 15. Ngoài phạm vi và task kế tiếp

Ngoài phạm vi:

- Google OAuth và liên kết nhiều phương thức đăng nhập.
- OTP xác minh số điện thoại.
- Reset/change password.
- Email.
- Admin CRUD user UI.
- JWT hoặc REST authentication.
- Xóa avatar cũ trên Cloudinary.

Task kế tiếp sau feature này:

1. Google OAuth/link account.
2. Tag và lựa chọn level/topic trước matching.
3. LearningSession và matchmaking.
4. Ràng Agora token với authenticated user và session.

## 16. Quyết định cần reviewer xác nhận

1. Đồng ý chọn **phone/password** làm luồng authentication đầu tiên, Google để task sau?
2. Đồng ý cho tài khoản `isPhoneVerified=false` sử dụng hệ thống trong MVP cho đến khi có OTP?
3. Đồng ý chuẩn hóa toàn bộ phone Việt Nam về `+84xxxxxxxxx`?
4. Đồng ý bảo vệ `/video-call/**` và `/api/agora/**` ngay trong feature này?

### Kết quả review

- [x] Chọn phone/password trước, Google để task sau.
- [x] Cho phép tài khoản `isPhoneVerified=false` sử dụng MVP.
- [x] Chuẩn hóa số điện thoại Việt Nam về `+84xxxxxxxxx`.
- [x] Bảo vệ `/video-call/**` và `/api/agora/**` ngay trong feature này.
