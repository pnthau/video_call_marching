# Spec: Hoàn thiện User Profile và Avatar Cloudinary

## Trạng thái

- Trạng thái: **DRAFT — chờ duyệt**.
- Nhánh áp dụng: `codex/feature-user-authentication`.
- Mục tiêu: hoàn thiện các hạng mục User/Google OAuth/Avatar đã được yêu cầu trong `spec_user_authentication.md`, không mở rộng sang matchmaking, tag hoặc video call.

## 1. Kết quả rà soát hiện tại

Đã có:

- `CloudinaryAvatarStorageService` upload JPEG/PNG/WebP, giới hạn 5 MB.
- Form `/profile/edit` cho username, Japanese level và avatar.
- `UserServiceImpl` lưu URL avatar Cloudinary vào `users.avatar_url`.
- Google OIDC tạo `User` và `SocialAccount` khi chưa có liên kết.

Thiếu hoặc sai so với spec đã duyệt:

1. Ảnh avatar cũ không bị xóa sau khi avatar mới upload thành công; Cloudinary sẽ tích lũy ảnh mồ côi.
2. Không lưu `public_id` của Cloudinary, nên không thể xóa chính xác ảnh cũ bằng API Cloudinary.
3. `GoogleOidcUserService` hiện cho phép `email_verified = false` và `status = DISABLED`, trái với spec.
4. Username Google đang ghép email với `sub`, bỏ qua Full Name và có thể vượt giới hạn 50 ký tự.
5. Bảng `social_accounts` chưa có unique constraint cho cặp `(provider, provider_id)`.
6. `UserStatusInitializer` tự đổi mọi user `DISABLED` thành `ACTIVE` khi khởi động, vô hiệu hóa hoàn toàn chức năng khóa tài khoản.
7. `DuplicatePhoneNumberException` còn sót lại dù luồng phone/password đã bị loại bỏ.
8. Test chưa kiểm tra upload lỗi không làm thay đổi user, xóa asset cũ, email Google chưa xác minh và user bị vô hiệu hóa.

## 2. Phạm vi triển khai

### 2.1 Avatar Cloudinary

- Bổ sung `avatarPublicId` nullable cho `User`, lưu `public_id` trả về từ Cloudinary.
- Đổi storage interface để upload trả về cả `secureUrl` và `publicId`.
- Khi user chọn avatar mới:
  1. Validate file trước khi gọi Cloudinary: JPEG/PNG/WebP, không rỗng, tối đa 5 MB.
  2. Upload ảnh mới vào folder `videocall-marching/avatars`.
  3. Cập nhật URL và public ID mới trong `User`.
  4. Sau khi database commit thành công, xóa asset cũ nếu có public ID.
- Nếu upload ảnh mới thất bại: không đổi avatar trong database, không xóa ảnh cũ, trả lỗi tại field `avatar`.
- Không thêm endpoint xóa avatar riêng trong phạm vi này; user giữ ảnh Google/avatar hiện tại khi không gửi file.

### 2.2 Google OIDC và account safety

- Yêu cầu claim `sub`, `email` và `email_verified = true`; thiếu hoặc không hợp lệ thì từ chối OAuth2 login.
- Nếu user liên kết có `status = DISABLED`, từ chối OAuth2 login.
- Xóa `UserStatusInitializer`; status chỉ được thay đổi bởi chức năng quản trị trong tương lai, không tự thay đổi khi app khởi động.
- Tạo username từ Full Name của Google; nếu trống dùng phần trước `@` của email. Chuẩn hóa khoảng trắng và cắt tối đa 50 ký tự; bảo đảm tối thiểu 2 ký tự bằng fallback an toàn.
- Vẫn lookup trước theo `(provider = GOOGLE, providerId = sub)`, sau đó lookup theo email để không tạo user trùng email.
- Bổ sung unique constraint `(provider, provider_id)` cho `SocialAccount` ở entity/database. Không thay đổi định danh Google đang lưu.

### 2.3 Profile UX và error handling

- Giữ các field user được phép chỉnh: username, currentLevel, avatar.
- Không cho chỉnh email, role, status hoặc trustScore từ form hay request.
- Hiển thị avatar hiện tại trong trang edit, kèm nhắc rằng bỏ trống sẽ giữ ảnh hiện tại.
- Khi upload/validation lỗi, trả lại form cùng dữ liệu hiện có và lỗi tại field avatar.

## 3. Các file dự kiến thay đổi

- `entity/User.java`, `entity/SocialAccount.java`
- `service/AvatarStorageService.java`, `service/impl/CloudinaryAvatarStorageService.java`, `service/impl/UserServiceImpl.java`, `service/impl/GoogleOidcUserService.java`
- `dto/user/UserProfileResponse.java` (nếu view cần hiển thị trạng thái avatar)
- `utils/CloudinaryConfig.java` (chuyển sang constructor injection)
- `config/UserStatusInitializer.java` và `exception/DuplicatePhoneNumberException.java` (xóa code phone/status cũ)
- `templates/users/profile_edit.html`
- Unit tests của avatar/user service và Google OIDC service

Không thêm dependency mới và không sửa OAuth credentials/config.

## 4. Dữ liệu và migration

- Hibernate `ddl-auto=update` sẽ thêm nullable column `avatar_public_id` và unique index cho social account trên database local.
- Không drop bảng, không xóa user hoặc ảnh hiện hữu.
- Avatar tồn tại trước thay đổi không có `public_id`, do đó không thể tự xóa từ Cloudinary; lần upload avatar mới vẫn hoạt động bình thường.

## 5. Acceptance criteria

1. Upload avatar JPEG/PNG/WebP <= 5 MB lưu `secure_url` và `public_id`.
2. Upload avatar mới xóa asset cũ chỉ sau khi user đã lưu thành công.
3. Upload lỗi/invalid file giữ nguyên URL và public ID cũ.
4. Google account email chưa xác minh không đăng nhập được.
5. Google-linked user `DISABLED` không đăng nhập được.
6. Login Google lần đầu tạo đúng một `User` và một `SocialAccount`; lần sau không tạo trùng.
7. Username mới lấy ưu tiên từ Google Full Name và luôn thỏa 2–50 ký tự.
8. `./gradlew.bat test` và `./gradlew.bat build` pass trước khi commit.

## 6. Ngoài phạm vi

- Quản trị user, khóa/mở khóa user bằng giao diện admin.
- Crop/resize avatar phía client, CDN transformation riêng, hoặc endpoint REST profile.
- Xóa tài khoản và xóa toàn bộ Cloudinary assets.
- Các tính năng tag, matching, rating, Agora/video call.
