# Spec: Google Social Login (Only Google, No Phone)

## Trạng thái

- **UPDATED** ngày 2026-08-17.
- Quyết định thay đổi: Loại bỏ hoàn toàn chức năng đăng ký, đăng nhập bằng số điện thoại và mật khẩu. Hệ thống chuyển sang sử dụng duy nhất phương thức đăng nhập và đăng ký tự động qua tài khoản Google (OAuth2 / OIDC).

## Mục tiêu

- Người dùng đăng nhập/đăng ký vào hệ thống duy nhất qua nút "Đăng nhập bằng Google".
- Không có form nhập số điện thoại, mật khẩu hay mã OTP.
- Lần đăng nhập đầu tiên qua Google sẽ tự động tạo tài khoản người dùng mới; những lần đăng nhập sau sẽ vào tài khoản hiện có.

## Thiết kế Google OAuth2

- Sử dụng Spring Security OAuth2 Client và Google OpenID Connect (OIDC).
- Sử dụng claim `sub` từ Google ID Token làm định danh duy nhất cho tài khoản Google.
- Lưu liên kết tài khoản trong bảng `SocialAccount` (`provider = 'GOOGLE'`, `providerId = sub`).
- Yêu cầu email từ Google phải được xác minh (`email_verified = true`). Nếu email chưa xác minh, từ chối đăng nhập.

## Thay đổi về User Entity & Database

### User Entity
- Loại bỏ hoàn toàn các thuộc tính liên quan đến số điện thoại và mật khẩu:
  - Xóa trường `phoneNumber`.
  - Xóa trường `passwordHash`.
  - Xóa trường `isPhoneVerified`.
- `email`: Bắt buộc (`nullable = false`), là duy nhất (`unique = true`).
- `username` (tên hiển thị): Mặc định lấy từ Full Name của Google. Nếu trống, lấy phần tiền tố của email trước ký tự `@`.
- `avatarUrl`: Lưu URL ảnh đại diện của Google.
- Giữ nguyên các thuộc tính phân quyền, trình độ và trạng thái (`role = USER`, `status = ACTIVE`, `currentLevel = N5`, `trustScore = 0.0f`).

### SocialAccount Entity
- Liên kết n-1 với `User`.
- `provider`: "GOOGLE".
- `providerId`: lưu giá trị `sub` từ Google.

## Luồng xác thực & UI

- Trang `/login` chỉ hiển thị một nút duy nhất: "Đăng nhập bằng Google".
- Loại bỏ hoàn toàn trang đăng ký `/register` và các form liên quan.
- Khi người dùng click vào nút Google, hệ thống chuyển hướng tới `/oauth2/authorization/google`.
- Sau khi Google xác thực thành công và redirect về, `GoogleOidcUserService` xử lý:
  - Nếu đã tồn tại `SocialAccount` tương ứng với `providerId`: tiến hành đăng nhập vào User liên kết.
  - Nếu chưa tồn tại: tạo mới thực thể `User` từ thông tin Google cung cấp, sau đó lưu bản ghi `SocialAccount` liên kết và đăng nhập.
- Đăng nhập thành công sẽ chuyển hướng về `/profile`.
- Đăng xuất (`logout`) hủy session hiện tại và xóa cookie `JSESSIONID`.

## Kiểm thử và nghiệm thu

- Đăng nhập lần đầu bằng Google: Tạo tài khoản mới trong database và đăng nhập thành công.
- Đăng nhập lần tiếp theo bằng Google: Đăng nhập thành công vào đúng tài khoản cũ, không nhân bản tài khoản.
- Tài khoản ở trạng thái `DISABLED` bị từ chối đăng nhập.
- Xóa các test case liên quan đến phone/password registration, phone validation và OTP.
- `./gradlew clean build` thành công.
