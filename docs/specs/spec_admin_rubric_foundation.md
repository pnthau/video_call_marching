# Spec: Admin & Rubric Foundation

## 1. Trạng thái

* **APPROVED — implementation-ready từ ngày 2026-08-31**.
* Phụ thuộc vào LearningSession Lifecycle V2 trong PR #9.
* Phạm vi: admin authorization, admin dashboard, user management hiện có và nền Rubric cố định.
* Không triển khai Peer Rating, RatingDetail hoặc trust-score algorithm trong spec này.
* Không sửa Flyway V1–V3.

## 2. Mục tiêu

Hoàn thiện nền quản trị tối thiểu cho MVP:

```text
Google authenticated user
    -> ROLE_ADMIN authorization
    -> Admin dashboard
    -> User management
    -> Fixed Rubric administration
```

Rubric foundation cung cấp đúng 7 tiêu chí cố định để Giai đoạn Peer Rating sử dụng sau này.

## 3. Ngoài phạm vi

* Submit Peer Rating.
* `RatingDetail`.
* Tính hoặc cập nhật trust score.
* Create/delete rubric tùy ý.
* Thay đổi stable rubric criteria.
* Buddy.
* Group call.
* Redis/distributed matching.
* Social login ngoài Google.

## 4. Authorization

* Toàn bộ `/admin/**` yêu cầu `ROLE_ADMIN`.
* Guest truy cập `/admin/**` được chuyển tới login theo Spring Security entry point.
* Authenticated `USER` truy cập `/admin/**` nhận `403 Forbidden`.
* `ADMIN` truy cập được.
* Authorization phải được cấu hình trong `SecurityConfig`.
* Không chỉ ẩn menu ở frontend.
* Không tin role hoặc user ID do client gửi.
* Người dùng không được tự nâng role.
* POST admin bắt buộc có CSRF token.
* Không thêm admin account hoặc credential hardcode vào source.

## 5. Admin dashboard

Endpoint:

```http
GET /admin
```

Dashboard:

* Chỉ dành cho `ADMIN`.
* Cung cấp navigation tới user management và rubric management.
* Không hiển thị OAuth provider ID, token, cookie hoặc secret.
* Không trả entity trực tiếp qua API/controller boundary.
* Dùng Thymeleaf và layout/style hiện có.

## 6. User management

Giữ phạm vi quản lý user hiện có đã được audit.

Admin được phép:

* Xem danh sách user.
* Xem các field quản trị được phép.
* Cập nhật các field đã được whitelist trong DTO.
* Enable/disable tài khoản nếu implementation hiện tại đã hỗ trợ.

Admin không được cập nhật bằng mass assignment:

* `id`.
* OAuth provider/providerId.
* Password hoặc credential.
* `createdAt`.
* `updatedAt`.
* Trust score tùy ý.
* Field nội bộ không xuất hiện trong DTO.
* Username/email ngoài contract đã chốt.

Quy tắc:

* Email identity từ Google là read-only.
* Username phải validation và unique.
* Duplicate username trả validation/business error rõ ràng.
* Không bind request trực tiếp vào `User` entity.
* Không để admin tự vô hiệu hóa chính mình nếu việc đó làm hệ thống không còn admin hợp lệ; implementation phải chặn hoặc tuân theo policy được test.
* Không log email/providerId hoặc SecurityContext chi tiết không cần thiết.

## 7. Rubric criteria cố định

Hệ thống có đúng 7 criteria:

1. `ACCURACY`.
2. `FLUENCY`.
3. `PRONUNCIATION_INTONATION`.
4. `STRUCTURE_LOGIC`.
5. `CONTENT_INTERESTINGNESS`.
6. `BODY_LANGUAGE`.
7. `ENTHUSIASM_CONFIDENCE`.

Không có:

```text
SUPPORTIVENESS
```

Không được thêm criteria thứ tám qua database, initializer, admin UI hoặc API.

## 8. Rubric model

Rubric có tối thiểu:

* `id`.
* `criteria`.
* `displayName`.
* `description`.
* `isActive`.
* `createdAt`.
* `updatedAt`.

Quy tắc:

* `criteria` là stable business code.
* `criteria` unique và immutable.
* Admin không được sửa `criteria`.
* Admin được sửa `displayName`.
* Admin được sửa `description`.
* Admin được bật/tắt `isActive`.
* Không có create/delete Rubric endpoint.
* Việc sửa tên/mô tả sau này không được làm thay đổi stable criteria.

## 9. Invariant đúng 7 rubric

Invariant được bảo vệ ở nhiều lớp.

### 9.1 Database whitelist

Migration mới phải giới hạn `criteria` vào đúng 7 giá trị được duyệt.

Với MySQL 8, sử dụng `CHECK` constraint tương đương:

```sql
CHECK (
    criteria IN (
        'ACCURACY',
        'FLUENCY',
        'PRONUNCIATION_INTONATION',
        'STRUCTURE_LOGIC',
        'CONTENT_INTERESTINGNESS',
        'BODY_LANGUAGE',
        'ENTHUSIASM_CONFIDENCE'
    )
)
```

Đồng thời:

```text
UNIQUE(criteria)
```

Database phải từ chối `SUPPORTIVENESS` hoặc criteria không nằm trong whitelist.

### 9.2 DataInitializer

`DataInitializer` là seed source.

Khi startup:

1. Đọc toàn bộ rubric hiện có.
2. Phát hiện criteria không nằm trong whitelist.
3. Nếu có criteria lạ, startup phải fail rõ ràng; không tự xóa dữ liệu.
4. Chỉ thêm criteria còn thiếu.
5. Không tạo duplicate.
6. Sau seed, xác minh có đúng 7 criteria.
7. Nếu vẫn thiếu/thừa/sai criteria, startup fail.
8. Không reset `displayName`, `description` hoặc `isActive` của rubric đã tồn tại.
9. Restart phải giữ các chỉnh sửa của admin.

Không seed đồng thời bằng Flyway và `DataInitializer`.

### 9.3 Service/UI

* Không có create rubric service hoặc endpoint.
* Không có delete rubric service hoặc endpoint.
* DTO edit không chứa `criteria`.
* Client không thể gửi criteria mới.
* Service chỉ update display name, description và active state.

## 10. Dữ liệu có criteria lạ

Nếu database hiện hữu đã có criteria ngoài whitelist:

* Migration hoặc startup validation phải dừng.
* Không tự động xóa.
* Không âm thầm đổi sang criteria khác.
* Báo rõ criteria lạ và yêu cầu xử lý dữ liệu có kiểm soát.
* Backup trước khi xử lý.
* `SUPPORTIVENESS` không được tự động đưa vào bộ 7 tiêu chí.

## 11. Flyway migration

V1–V3 đã phát hành trong PR #9 và là immutable.

Admin/Rubric sử dụng migration mới:

```text
V4__admin_rubric_schema.sql
```

V4 chịu trách nhiệm:

* Tạo bảng `rubrics` nếu chưa có trong baseline tương ứng.
* Tạo unique constraint cho `criteria`.
* Tạo whitelist check constraint.
* Tạo các cột và index cần thiết.
* Không seed rubric.
* Không tạo `RatingDetail`.
* Không sửa dữ liệu LearningSession/SessionPresence.
* Không sửa V1–V3.

Nếu V4 đã được push/phát hành rồi phát hiện lỗi, sửa bằng V5; không chỉnh checksum V4.

Hibernate tiếp tục:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

## 12. Rubric endpoints

```http
GET  /admin/rubrics
GET  /admin/rubrics/edit/{id}
POST /admin/rubrics/edit/{id}
POST /admin/rubrics/toggle/{id}
```

Không có:

```http
POST   /admin/rubrics/create
POST   /admin/rubrics/delete/{id}
DELETE /admin/rubrics/{id}
```

Controller dùng DTO và Service/ServiceImpl.

## 13. Validation

* `displayName` bắt buộc và có giới hạn độ dài theo entity/schema.
* `description` có giới hạn độ dài.
* Khoảng trắng đầu/cuối được chuẩn hóa.
* Empty/blank display name bị từ chối.
* Rubric không tồn tại trả lỗi phù hợp.
* Criteria không xuất hiện trong editable request.
* Toggle lặp phải tạo trạng thái dự đoán được theo contract UI/service.
* Validation error không lộ stack trace.

## 14. Seed behavior

Lần startup đầu:

* Tạo đúng 7 rubric.
* Stable criteria đúng whitelist.
* Giá trị mặc định được khai báo tập trung.

Lần startup tiếp theo:

* Không tạo thêm row.
* Không reset custom `displayName`.
* Không reset custom `description`.
* Không reset `isActive`.

Nếu admin đã chỉnh rubric, restart phải giữ nguyên.

## 15. Test matrix

### Authorization

* Guest bị redirect khỏi `/admin/**`.
* `USER` nhận `403`.
* `ADMIN` truy cập được.
* POST thiếu/sai CSRF bị từ chối.
* Không privilege escalation.
* Google OIDC giữ đúng `ROLE_ADMIN` của admin hiện hữu.

### Rubric invariant

* Database từ chối `SUPPORTIVENESS`.
* Database từ chối criteria lạ.
* Database từ chối duplicate criteria.
* First startup tạo đúng 7.
* Second startup vẫn đúng 7.
* Missing criteria được bổ sung.
* Unknown criteria làm startup fail.
* Custom display name không bị reset.
* Custom description không bị reset.
* Inactive state không bị reset.

### Rubric service/controller

* List đúng 7 rubric.
* Edit display name.
* Edit description.
* Toggle active.
* Không edit criteria.
* Không create/delete.
* Validation blank/duplicate/invalid.
* Rubric không tồn tại.

### User management

* Email identity read-only.
* Sensitive mass-assignment fields bị bỏ qua hoặc từ chối.
* Username validation.
* Duplicate username.
* Role/security boundary.
* CSRF.

### Migration

* Clean MySQL: V1–V4 PASS.
* Upgrade MySQL: V1–V3 database chạy V4 PASS.
* Hibernate validate PASS.
* Lifecycle/session data không đổi.
* Database có đúng 7 rubric sau initializer.
* Restart vẫn đúng 7 rubric.

### Regression

* Admin/Rubric targeted tests PASS.
* SecurityIntegrationTests PASS.
* Full test suite PASS.
* Gradle build PASS.
* GitHub Actions MySQL build PASS.

## 16. Manual acceptance

Trên database test riêng:

1. Guest không vào `/admin`.
2. `USER` không vào `/admin`.
3. `ADMIN` vào dashboard.
4. `/admin/rubrics` hiển thị đúng 7 rubric.
5. Không có `SUPPORTIVENESS`.
6. Sửa display name thành công.
7. Sửa description thành công.
8. Toggle active thành công.
9. Restart application.
10. Các giá trị đã sửa vẫn được giữ.
11. Không có create/delete.
12. User management không sửa được email/field nhạy cảm.
13. Lifecycle V2 smoke test không regression.

## 17. Definition of Done

* Spec được đánh dấu `APPROVED`.
* V1–V3 không thay đổi.
* V4 migration PASS trên clean/upgrade MySQL.
* Database whitelist đúng 7 criteria.
* Initializer bổ sung thiếu và fail khi có criteria lạ.
* Seed/restart idempotency PASS.
* Security/CSRF PASS.
* Targeted tests PASS.
* Full tests PASS.
* Build PASS.
* CI PASS.
* Manual acceptance PASS.
* Không còn finding HIGH/MEDIUM.
* Không triển khai Peer Rating.
