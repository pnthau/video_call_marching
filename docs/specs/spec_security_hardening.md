# Spec: Security Hardening

## 1. Trạng thái

- **Slice 1: APPROVED — PASS.**
- **Slice 2: APPROVED FOR IMPLEMENTATION — sau khi spec delta re-review PASS.**
- **Slice 3: AUTHORIZED — READY FOR IMPLEMENTATION.**
- Target path khi đưa vào repository: `docs/specs/spec_security_hardening.md`.
- Target branch: `feature/security-hardening`.
- Dependency base: `main`, sau các thay đổi LearningSession Lifecycle V2, Agora Call Reliability và Admin/Rubric đã merge.
- Slice 1 là `APPROVED — PASS` và `CLOSED`; contract Slice 1 là frozen và không được sửa trong Slice 2.
- Slice 2 là `APPROVED FOR IMPLEMENTATION` sau khi spec delta re-review `PASS`, theo contract tại mục 8.
- Slice 3 là `AUTHORIZED — READY FOR IMPLEMENTATION`; các quyết định quota, response, dependency và registry đã được chốt tại mục 9.

## 2. Mục tiêu

Gia cố các HTTP boundary hiện hữu mà không thay đổi nghiệp vụ đã nghiệm thu:

1. Chuẩn hóa API/MVC exception handling và không lộ chi tiết nội bộ.
2. Xác thực avatar theo nhiều lớp trước khi gửi dữ liệu tới Cloudinary.
3. Giới hạn tần suất các endpoint nhạy cảm theo từng authenticated user.
4. Giữ nguyên authentication, authorization, CSRF, LearningSession Lifecycle V2 và Agora reliability.

## 3. Baseline đã xác minh

- Ứng dụng dùng Java 17, Spring Boot 4.1.0, Spring MVC, Spring Security và Thymeleaf.
- `LearningSessionController` là REST boundary tại `/api/sessions/**` và hiện còn xử lý lặp `try/catch` cho `SessionNotFoundException`, `SessionAccessDeniedException` và `SessionConflictException`.
- `AdminExceptionHandler` đang xử lý MVC error riêng cho `AdminController`; behavior này phải được giữ nguyên.
- `ProfileController` trả lại form edit và gắn lỗi avatar vào `BindingResult`.
- `CloudinaryAvatarStorageService` đã giới hạn file 5 MiB và từng allow declared MIME `image/jpeg`, `image/png`, `image/webp`, nhưng chưa xác minh nội dung ảnh thật, dimension/pixel budget hoặc server-owned public ID.
- Chưa có rate limiter trong ứng dụng.

## 4. Ngoài phạm vi

- Peer Rating, RatingDetail và trust-score algorithm.
- Redis hoặc persistence mới cho matchmaking.
- API Gateway hoặc Spring Cloud Gateway.
- Đổi package name.
- Migration mới hoặc sửa V1–V4.
- Thay đổi database schema.
- Thay đổi Google OAuth flow.
- Thay đổi WebSocket/STOMP authentication và origin contract đã nghiệm thu.
- Thay đổi Lifecycle V2, scheduler, finalizer, session/presence semantics.
- Thay đổi Agora token generation, recovery, media state machine hoặc SDK version.
- Antivirus/CDR hoặc moderation nội dung ảnh bằng dịch vụ bên ngoài.
- Rate limiting phân tán cho nhiều application instance.

## 5. Invariant bắt buộc

- Người chưa đăng nhập không được biến thành authenticated user bởi exception/rate-limit code.
- Non-participant tiếp tục nhận `403`.
- Session không tồn tại tiếp tục nhận `404`.
- Token/join của terminal session tiếp tục nhận `409`.
- CSRF cho HTTP mutation tiếp tục do Spring Security thực thi.
- Admin authorization và `AdminExceptionHandler` không bị giảm phạm vi bảo vệ.
- Rate limiting không tạo hoặc thay đổi `LearningSession`, `SessionPresence` hay domain state.
- Avatar validation failure không được gọi Cloudinary.
- Không ghi log hoặc trả về token, cookie, credential, CSRF value, Cloudinary signature, raw provider response hoặc stack trace.
- Không thêm secret/default credential vào source, test fixture hoặc configuration.

## 6. Implementation strategy

Triển khai tuần tự ba slice:

1. Exception Boundary.
2. Avatar Upload Validation.
3. Endpoint-specific Rate Limiting.

Mỗi slice phải trải qua:

1. Implement.
2. Targeted tests.
3. Freeze exact diff.
4. Independent Review read-only.
5. Repair findings được giao.
6. Delta re-review.
7. Slice PASS.

Không chuyển slice nếu còn finding CRITICAL/HIGH/MEDIUM hoặc contract chưa được quyết định.

## 7. Slice 1 — Exception Boundary

### 7.1. API error model

API error response dùng cấu trúc ổn định:

```json
{
  "code": "SESSION_NOT_FOUND",
  "message": "Không tìm thấy phiên học.",
  "timestamp": "2026-09-05T00:00:00Z",
  "path": "/api/sessions/123"
}
```

Field bắt buộc:

- `code`: mã máy đọc ổn định.
- `message`: thông báo đã sanitize cho người dùng.
- `timestamp`: UTC `Instant` do server tạo.
- `path`: request URI, không gồm query string hoặc dữ liệu nhạy cảm.

Không được trả:

- Exception class.
- Stack trace.
- SQL/provider message.
- `exception.getMessage()` chưa được map/sanitize.
- User ID, token, cookie hoặc credential.

### 7.2. Mapping bắt buộc

| Exception/condition | HTTP | Code | Public message |
|---|---:|---|---|
| `UserNotFoundException` | 404 | `USER_NOT_FOUND` | `Không tìm thấy người dùng.` |
| `SessionNotFoundException` | 404 | `SESSION_NOT_FOUND` | `Không tìm thấy phiên học.` |
| `SessionAccessDeniedException` | 403 | `SESSION_ACCESS_DENIED` | `Bạn không có quyền truy cập phiên học này.` |
| `SessionConflictException` | 409 | `SESSION_CONFLICT` | `Trạng thái phiên học không cho phép thao tác này.` |
| Rate limit exhausted | 429 | `RATE_LIMIT_EXCEEDED` | `Bạn thao tác quá nhanh. Vui lòng thử lại sau.` |
| Unexpected API exception | 500 | `INTERNAL_ERROR` | `Đã xảy ra lỗi hệ thống.` |

### 7.3. Boundary separation

- REST error handler chỉ áp dụng cho API controller được allowlist, bắt đầu bằng `LearningSessionController`.
- Không áp dụng global JSON handler lên `ProfileController`, `WebController` hoặc Admin MVC controllers.
- `AdminExceptionHandler` tiếp tục trả view `admin/not-found` cho behavior hiện hữu.
- Profile validation tiếp tục trả `users/profile_edit` và gắn lỗi đúng field/form.
- Authentication entry point, access-denied handler và CSRF failure của Spring Security không được handler chung chuyển thành `500`.

### 7.4. Controller cleanup

- Thay generic `RuntimeException("User not found")` bằng exception domain đã chốt.
- Bỏ `try/catch` lặp trong API controller chỉ sau khi advice chứng minh giữ nguyên status contract.
- Không thay đổi service semantics hoặc DTO success response.
- `204 No Content` của active-session lookup phải giữ nguyên.

### 7.5. Logging

- Unexpected API error được log server-side bằng sanitized category và correlation/request ID nếu đã có.
- Không log raw request body, token response, cookie/session ID hoặc exception/provider message nhạy cảm.
- Expected business exception `403/404/409/429` không cần full stack trace.

### 7.6. Slice 1 tests tối thiểu

- Mỗi mapping `403/404/409/500` đúng status, code và public message.
- Missing authenticated application user trả `404 USER_NOT_FOUND`.
- Terminal token và terminal join vẫn trả `409`.
- Non-participant vẫn trả `403`.
- Missing session vẫn trả `404`.
- Active session absent vẫn trả `204`.
- Response không chứa exception class, stack trace hoặc raw internal message.
- Guest/authentication, access denied và CSRF regression giữ nguyên.
- Admin not-found view giữ nguyên.
- Profile validation flow giữ nguyên.

## 8. Slice 2 — Avatar Upload Validation

### 8.0. Approval and fixed implementation boundary

- Slice 2 là `APPROVED FOR IMPLEMENTATION` sau khi spec delta re-review `PASS`. `ProfileController` phải giữ MVC flow hiện hữu: trả về `users/profile_edit` và gắn lỗi avatar vào `BindingResult`.
- Validation phải hoàn tất trước mọi lời gọi Cloudinary. Validation failure không được upload, xóa hoặc thay đổi avatar hiện hữu.
- Việc xóa avatar cũ sau khi DB transaction của avatar mới commit thành công giữ nguyên contract hiện có; không được chuyển việc xóa này lên trước commit.
- Không thêm image-processing dependency. Chỉ dùng Java 17 JDK `ImageIO` và `ImageReader` cho JPEG/PNG để detect/decode/header dimension.
- Slice 2 không cấp quyền sửa `application.properties`, multipart/request configuration hoặc bất kỳ configuration file nào.

### 8.1. Accepted formats

Phase này chỉ chấp nhận nội dung ảnh hợp lệ thực tế là:

- JPEG: `image/jpeg`.
- PNG: `image/png`.

Phải từ chối WebP, GIF, SVG và mọi format khác, không phụ thuộc declared MIME, extension hay filename:

- SVG.
- GIF.
- BMP/TIFF/ICO.
- Archive/document/video/audio.
- WebP.

- WebP bị từ chối dứt khoát trong Slice 2; không có dependency, decoder hoặc approval WebP đang chờ.

Lý do loại WebP khỏi contract hiện tại: Java `ImageIO` mặc định không bảo đảm decode WebP; declared MIME allowlist không đủ làm bằng chứng nội dung hợp lệ. Không có ngoại lệ format nào trong Slice 2.

### 8.2. Limits

| Limit | Giá trị |
|---|---:|
| Số file mỗi profile update | 1 |
| File size tối đa | 5 MiB (`5,242,880` bytes) |
| Width tối đa | 4096 px |
| Height tối đa | 4096 px |
| Tổng pixel tối đa | 16,777,216 |

- File có kích thước đúng `5 * 1024 * 1024` bytes (`5,242,880`) được phép; chỉ file lớn hơn giới hạn này bị từ chối.
- Pixel count phải tính bằng `long` để tránh integer overflow.
- Slice 2 không được thay đổi multipart/request size configuration; giới hạn này được thực thi trong validation của Slice 2.

### 8.3. Validation order

Trước khi gọi Cloudinary:

1. Xác minh authenticated owner của profile.
2. File `null` hoặc rỗng: reject `AVATAR_EMPTY`.
3. File lớn hơn `5 * 1024 * 1024` bytes: reject `AVATAR_TOO_LARGE`; đúng giới hạn được phép.
4. Declared MIME `null`, hoặc không đúng chính xác `image/jpeg` hay `image/png`: reject `AVATAR_TYPE_NOT_ALLOWED`. Bao gồm declared WebP, GIF, SVG và mọi MIME khác.
5. Chỉ với declared MIME JPEG/PNG hợp lệ, đọc signature bằng stream bounded. Bytes không có valid JPEG/PNG signature: reject `AVATAR_INVALID_CONTENT`. Không parse SVG/XML ở bất kỳ nhánh nào.
6. JPEG signature với declared PNG MIME, hoặc PNG signature với declared JPEG MIME: reject `AVATAR_TYPE_MISMATCH`.
7. Với signature JPEG/PNG đã nhận diện, dùng Java 17 `ImageIO`/`ImageReader` đọc metadata. Stream truncated/malformed, không có `ImageReader`, không decode được, metadata không đọc được, hoặc width/height không dương: reject `AVATAR_INVALID_CONTENT`.
8. Tính `((long) width) * height`; width > 4096, height > 4096, hoặc pixel > `16_777_216L`: reject `AVATAR_DIMENSIONS_EXCEEDED`.
9. Chỉ sau toàn bộ validation mới gọi Cloudinary.

Không được chỉ tin:

- `MultipartFile.getContentType()`.
- Extension.
- `getOriginalFilename()`.
- Cloudinary tự phát hiện loại file.

### 8.4. Filename và Cloudinary identity

- Original filename không được dùng làm path, folder hoặc `public_id`.
- Server tạo opaque identifier bằng UUID hoặc giá trị random tương đương.
- Public ID có thể namespace theo authenticated user nhưng không nhận user ID từ client.
- Folder cố định phải do server cấu hình.
- Chỉ chấp nhận `secure_url` và `public_id` hợp lệ từ kết quả provider.
- Nếu upload mới thành công nhưng cập nhật DB thất bại, phải có compensation cleanup hợp lý hoặc báo rõ orphan-risk trong review.
- Việc xóa avatar cũ phải tránh làm mất avatar đang dùng nếu update mới chưa commit thành công.

### 8.5. Error contract

| Condition | Code nội bộ/public form key |
|---|---|
| File `null` hoặc rỗng | `AVATAR_EMPTY` |
| File lớn hơn 5 MiB | `AVATAR_TOO_LARGE` |
| Declared MIME `null`, không phải `image/jpeg`/`image/png`, hoặc declared WebP/GIF/SVG/format khác | `AVATAR_TYPE_NOT_ALLOWED` |
| Declared JPEG/PNG nhưng bytes không có valid JPEG/PNG signature; random bytes declared JPEG/PNG | `AVATAR_INVALID_CONTENT` |
| JPEG signature + PNG MIME, hoặc PNG signature + JPEG MIME | `AVATAR_TYPE_MISMATCH` |
| JPEG/PNG signature được nhận diện nhưng truncated/malformed, không có `ImageReader`, metadata unreadable, hoặc width/height không dương | `AVATAR_INVALID_CONTENT` |
| Width > 4096, height > 4096, hoặc `((long) width) * height > 16_777_216L` | `AVATAR_DIMENSIONS_EXCEEDED` |
| Provider/storage lỗi | `AVATAR_STORAGE_FAILED` |

- MVC có thể hiển thị thông báo tiếng Việt tương ứng tại field avatar.
- Không hiển thị raw Cloudinary message.
- Mapping trong bảng là deterministic và duy nhất theo thứ tự tại mục 8.3; không có alternative hoặc multiple-code allowance.

### 8.6. Logging/privacy

- Không log file bytes, original filename, Cloudinary credential/signature hoặc raw provider response.
- Không log public ID nếu không cần thiết cho vận hành.
- Cleanup failure chỉ log sanitized category và correlation ID.

### 8.7. Slice 2 tests tối thiểu

- Null và empty file.
- File đúng 5 MiB được chấp nhận ở size gate; file lớn hơn 5 MiB bị từ chối.
- Declared MIME `null`, WebP/GIF/SVG/MIME khác trả `AVATAR_TYPE_NOT_ALLOWED` trước khi đọc signature.
- Bytes random hoặc không có valid JPEG/PNG signature, khi declared JPEG/PNG, trả `AVATAR_INVALID_CONTENT`.
- JPEG signature + PNG MIME và PNG signature + JPEG MIME trả `AVATAR_TYPE_MISMATCH`.
- JPEG hợp lệ.
- PNG hợp lệ.
- Signature JPEG/PNG malformed/truncated, không có `ImageReader`, metadata unreadable hoặc width/height không dương trả `AVATAR_INVALID_CONTENT`.
- Width > 4096, height > 4096 và `((long) width) * height > 16_777_216L` trả `AVATAR_DIMENSIONS_EXCEEDED`.
- Pixel multiplication không overflow.
- SVG, GIF và WebP bị từ chối theo contract hiện tại.
- Original filename chứa traversal/double extension không ảnh hưởng storage identity.
- Cloudinary không được gọi khi validation fail.
- Public ID do server tạo và không phụ thuộc original filename/client user ID.
- Provider exception được sanitize thành `AVATAR_STORAGE_FAILED`.
- Profile form giữ dữ liệu cần thiết và hiện lỗi đúng vị trí.
- CSRF và authenticated ownership regression.

## 9. Slice 3 — Endpoint-specific Rate Limiting

### 9.1. Deployment model

- Phase này dùng in-memory token bucket cho đúng một application instance.
- Quota reset khi process restart là limitation được chấp nhận cho MVP.
- Không tuyên bố hỗ trợ enforcement toàn cục khi chạy nhiều instance.
- Khi scale nhiều instance, phải có spec riêng cho Redis/distributed store hoặc edge/gateway limiter.
- Slice 3 được authorize cho đúng một application instance; không tuyên bố distributed enforcement.

### 9.2. Exact library gate

- Không thêm rate-limit dependency; dùng internal in-memory token bucket với Java 17 `Clock`/ticker có thể kiểm soát trong test.
- Không dùng dynamic version, Redis hoặc Gateway dependency.
- Slice 3 đã được authorize; không còn dependency gate chờ xác minh.

### 9.3. Identity key

- Authenticated endpoint: key theo identity server-side từ `Authentication`, không nhận user ID từ request.
- Bucket phải namespace theo policy, ví dụ `TOKEN:<principal>` và `PROFILE_UPLOAD:<principal>`.
- Không dùng cookie/session ID hoặc email thô trong log/metrics.
- IP chỉ dùng làm fallback cho endpoint unauthenticated được phê duyệt riêng.
- Không tin `X-Forwarded-For`/`X-Real-IP` trừ khi trusted proxy configuration được chốt; mặc định dùng server-observed remote address.

### 9.4. Initial policies

| Policy | Endpoint/action | Capacity | Refill |
|---|---|---:|---:|
| `SESSION_TOKEN` | `GET /api/sessions/{id}/token` | 10 | 10/phút |
| `SESSION_ACTIVE` | `GET /api/sessions/active` | 30 | 30/phút |
| `SESSION_JOIN` | `POST /api/sessions/{id}/join-agora` | 10 | 10/phút |
| `SESSION_LEAVE` | `POST /api/sessions/{id}/leave-agora` | 10 | 10/phút |
| `AVATAR_UPLOAD` | profile update có non-empty avatar | 5 | 5/10 phút |
| `ADMIN_MUTATION` | POST mutation dưới `/admin/**` | 30 | 30/phút |

Policy cố định theo bảng phải giữ đúng quota; không được vô hiệu hóa limiter mặc định.

### 9.5. Matching rules

- Route matching phải xét HTTP method và normalized path; không dùng substring mơ hồ.
- Static assets, `/error`, OAuth callback và page GET thông thường không bị giới hạn.
- Không áp dụng HTTP filter lên STOMP frame và không tuyên bố đã rate-limit WebSocket messages.
- Profile request không có avatar không tiêu quota `AVATAR_UPLOAD`.
- Mỗi policy có bucket riêng; dùng token endpoint không được tiêu quota avatar hoặc admin.

### 9.6. Filter ordering

- Rate limiter không được bypass authentication/authorization hoặc tạo identity từ input client.
- Authenticated-user limiter phải chạy khi `SecurityContext` đã có authenticated principal.
- CSRF, authorization và existing Spring Security responses phải giữ nguyên.
- Thứ tự filter phải có integration test cho guest, USER, ADMIN và participant/non-participant.

### 9.7. Exhausted response

API response:

- HTTP `429 Too Many Requests`.
- `Retry-After` là số giây nguyên dương, tính từ bucket state.
- Body dùng `ApiErrorResponse` với code `RATE_LIMIT_EXCEEDED`.
- `Content-Type` phải là `application/json`.
- Không trả capacity, key, principal hoặc internal bucket state.

MVC response:

- HTTP `429`, body `ApiErrorResponse` sanitized, `Content-Type: application/json`, `Retry-After` là số nguyên dương.
- Profile quota exhaustion dùng static path `/profile/edit`; không trả view và không redirect.
- Không redirect loop qua endpoint cũng bị giới hạn.

### 9.8. Memory bound và cleanup

- Bucket registry in-memory phải có maximum size và expire-after-access/cleanup bounded.
- Maximum registry size là `10_000` buckets; entries expire-after-access theo clock/ticker controllable.
- Cleanup/eviction không được reset hoặc làm ảnh hưởng bucket đang active.
- Unknown/high-cardinality key không được làm map tăng vô hạn.
- Cleanup phải thread-safe.
- Eviction không được làm ảnh hưởng bucket của identity khác.
- Không persistence quota vào database.

### 9.9. Agora/Lifecycle safety

- Token renewal/reconnect hợp lệ phải nằm trong burst budget đã chốt.
- `429` không được biến session thành terminal hoặc gọi leave/end/finalize.
- Frontend phải xử lý `429` như retryable rate-limit condition theo `Retry-After`, không log token và không tạo retry storm.
- Backend terminal response `409` vẫn thắng mọi retry/recovery.

### 9.10. Slice 3 tests tối thiểu

- Request trong quota PASS.
- Request cuối capacity PASS; request tiếp theo trả `429`.
- `Retry-After` hợp lệ.
- Refill cho phép request mới bằng fake/controllable clock; không dùng sleep thật.
- User A và User B có bucket độc lập.
- Các policy có bucket độc lập.
- Concurrent requests không vượt capacity.
- Header IP giả không bypass identity resolution.
- Static/OAuth/error routes không bị giới hạn.
- Profile không có avatar không tiêu quota upload.
- Guest/authentication, USER/ADMIN authorization và CSRF giữ nguyên.
- `403/404/409` giữ nguyên khi quota còn.
- `429` không tạo lifecycle/domain mutation.
- Token frontend không retry storm và tôn trọng `Retry-After`.
- Registry eviction/expiration bounded và thread-safe.
- Application restart limitation được ghi nhận, không giả lập distributed guarantee.

## 10. Configuration contract — Slice 3 only

- Slice 3 dùng constants/constructor configuration cho quota, refill, bucket limit và clock; không sửa `application.properties`.
- Quota và bucket limit phải giữ đúng các giá trị đã chốt tại mục 9; clock/ticker được inject để test deterministically.
- Không bật endpoint quản trị để thay quota runtime.
- Không thêm secret hoặc configuration artifact mới.

## 11. Security và privacy requirements

Cấm log/commit:

- `.env` hoặc credential.
- Agora token/certificate.
- OAuth code/token/client secret.
- Cloudinary API secret/signature hoặc raw signed payload.
- Cookie/session ID và CSRF value.
- Uploaded file bytes hoặc original filename.
- Raw exception/provider response có thể chứa dữ liệu nhạy cảm.
- Database dump, startup log, terminal transcript hoặc build/IDE artifact.

Public error message phải ổn định và sanitized. Internal diagnostics chỉ dùng category/code cần thiết.

## 12. Migration và dependency rules

- V1–V4 immutable.
- Không tạo V5 trong phase này.
- Không thay entity/schema vì rate limiting và validation.
- Không thêm dependency rate limiter trong Slice 3; implementation dùng nội bộ, không dependency mới.
- Slice 2 dùng Java 17 `ImageIO`/`ImageReader` cho JPEG/PNG và không thêm WebP dependency; WebP bị từ chối dứt khoát.

## 13. Automated gate

Sau khi từng slice PASS và trước packaging:

1. Chạy toàn bộ targeted exception/security/upload/rate-limit tests.
2. Chạy existing Lifecycle V2, Agora reliability và Admin/Rubric regression tests.
3. Chạy:

   ```powershell
   .\gradlew.bat clean test --no-daemon
   .\gradlew.bat clean build --no-daemon
   ```

4. Báo chính xác tests, failures, errors, skipped và build result.
5. Chạy `git diff --check`.
6. Migration immutable check V1–V4.
7. Secret/privacy scan chỉ báo filename, không in secret value.
8. Phân loại toàn bộ changed/untracked files.
9. Không coi skipped/zero-test là PASS.

Không bắt buộc MySQL migration smoke mới nếu exact diff không chạm migration/entity/JPA/config datasource; reviewer phải xác minh điều kiện này. Full application-context startup vẫn bắt buộc.

## 14. Manual acceptance

Manual gate tối thiểu:

1. User đăng nhập vẫn xem/sửa profile bình thường.
2. JPEG và PNG hợp lệ upload thành công.
3. File giả ảnh, SVG và file quá lớn bị từ chối với thông báo an toàn.
4. Validation failure không thay avatar hiện tại.
5. Cloudinary failure giả lập/controlled không làm mất avatar hiện tại.
6. Gọi token endpoint trong quota vẫn giữ cuộc gọi/recovery bình thường.
7. Vượt quota nhận `429` và `Retry-After`; sau refill gọi lại thành công.
8. User A bị giới hạn không làm User B bị giới hạn.
9. Guest/USER/ADMIN authorization và CSRF giữ nguyên.
10. Terminal token/join vẫn `409`, non-participant vẫn `403`, missing vẫn `404`.

Manual test không được dùng development data hoặc ghi lại token/credential trong evidence.

## 15. Review format

Mỗi frozen snapshot, reviewer báo:

- Snapshot SHA hoặc exact diff scope.
- Files reviewed.
- CRITICAL/HIGH/MEDIUM/LOW findings.
- Test evidence và skipped count.
- Exception/status contract evidence.
- Upload validation/provider-boundary evidence.
- Rate-limit identity/concurrency/memory-bound evidence.
- Security/privacy evidence.
- Lifecycle/Agora/Admin regression evidence.
- Verdict `PASS`, `FAIL` hoặc `BLOCKED`.

Reviewer không sửa code. Nếu FAIL, Implement chỉ sửa findings được Lead giao và delta phải được re-review.

## 16. Packaging

Chỉ packaging sau automated và required manual gates PASS.

Không đưa vào delivery:

- Peer Rating.
- Migration/database artifacts.
- `.env`, logs hoặc transcripts.
- Build/IDE metadata.
- Temporary reports.
- Unrelated Admin, Lifecycle hoặc Agora refactor.

Commit plan đề xuất:

1. `docs: specify security hardening`
2. `feat: centralize API exception handling`
3. `feat: harden avatar upload validation`
4. `feat: add endpoint-specific rate limiting`
5. `test: cover security hardening gates`

Không dùng `git add .`. Push non-force và mở Draft PR từ `feature/security-hardening` vào `main`.

## 17. Definition of Done


- Slice 1 là `APPROVED — PASS`; exception boundary đã đạt gate và contract Slice 1 giữ frozen.
- Slice 2 là `APPROVED FOR IMPLEMENTATION` sau khi spec delta re-review `PASS`. Slice 2 hoàn thành khi JPEG/PNG được xác minh bằng content và dimension trước Cloudinary; unsupported/spoofed/oversized/malformed image bị chặn trước provider; public ID do server sở hữu; original filename không quyết định storage identity; và targeted tests/security gates của Slice 2 `PASS` không skip.
- Slice 3 là `AUTHORIZED — READY FOR IMPLEMENTATION`. Slice 3 hoàn thành khi rate limit theo authenticated identity và policy-specific bucket; `429`/`Retry-After` đúng contract, không retry storm hoặc domain mutation; bucket registry thread-safe và memory-bounded; và targeted tests/security gates của Slice 3 `PASS` không skip.
- Với slice đang triển khai, existing `403/404/409/204`, authentication, authorization và CSRF không regression; không migration, Peer Rating, Redis/Gateway hoặc package rename; và không còn finding CRITICAL/HIGH/MEDIUM trong phạm vi slice.
- Packaging chỉ sau khi các slice được authorize đã hoàn thành automated/manual gates tương ứng.

## 18. Quyết định Slice 3 đã chốt

Slice 3 đã được authorize. Quota, `429` JSON/MVC response, internal no-dependency implementation, controllable clock, maximum `10_000` buckets và expire-after-access cleanup tại mục 9 là contract cố định; không còn open decision nào chặn implementation. Slice 1 `APPROVED — PASS` và Slice 2 `APPROVED FOR IMPLEMENTATION` giữ nguyên.
