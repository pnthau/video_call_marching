# Spec: MVP Domain & Baseline

## 1. Trạng thái

- Trạng thái: **DRAFT - chờ review trước khi code**.
- Phạm vi: chốt domain và luồng MVP; chưa triển khai repository, service, controller hoặc migration.
- Kiến trúc áp dụng khi triển khai feature mới: Spring Boot MVC2, theo luồng
  `Repository -> Service -> ServiceImpl -> Controller`, dùng DTO ở biên Controller.
- Package hiện tại được giữ nguyên: `com.example.videocall_marching_language`.
- Agora chỉ phụ trách truyền tải audio/video thời gian thực. Backend sở hữu User,
  authentication, matchmaking, session, quyền tham gia phòng và rating.

## 2. Mục tiêu MVP

MVP phải hoàn thành được luồng nghiệp vụ sau:

`Đăng nhập -> chọn level và tag -> vào phòng chờ -> ghép cặp 1-1 -> gọi video -> hoàn tất buổi học -> đánh giá 7 tiêu chí`

Ngoài phạm vi MVP đầu tiên:

- Buddy.
- Group call/lễ hội 5 người.
- Nhiệm vụ dưới 5 phút.
- Cloud Recording.
- Redis matching.
- Gender Normalization Algorithm.
- Tự động nâng level.
- Facebook/LINE/Apple login.

## 3. Các quyết định domain đã chốt

### 3.1. Rubric gồm 7 tiêu chí

Mỗi tiêu chí được chấm từ 1 đến 5 sao:

1. `ACCURACY`: Độ chính xác về từ vựng và ngữ pháp.
2. `FLUENCY`: Độ trôi chảy, mức độ ngập ngừng hoặc vấp.
3. `PRONUNCIATION_INTONATION`: Phát âm và trọng âm.
4. `STRUCTURE_LOGIC`: Cấu trúc và tính mạch lạc.
5. `CONTENT_INTERESTINGNESS`: Chất lượng và mức độ thú vị của nội dung.
6. `BODY_LANGUAGE`: Ngôn ngữ cơ thể và giao tiếp mắt.
7. `ENTHUSIASM_CONFIDENCE`: Sự tự tin và hào hứng.

Quyết định cho MVP:

- Không đưa `SUPPORTIVENESS` (sự nhiệt tình hỗ trợ) vào bộ 7 tiêu chí chính.
- `totalScore` có miền giá trị từ 7 đến 35 và được backend tính từ 7 điểm chi tiết;
  client không được tự gửi tổng điểm.
- Thiết kế dữ liệu phải lưu được từng điểm thành phần, không chỉ `totalScore`.
- Admin có thể đổi tên/mô tả và trạng thái hoạt động của rubric; mã rubric là ổn định
  để không làm sai lịch sử đánh giá.

### 3.2. Hệ level tiếng Nhật

Level hợp lệ theo thứ tự từ thấp đến cao:

`N5 -> N4 -> N3 -> N2 -> N1`

Quyết định cho MVP:

- Dùng enum/domain value thay cho số nguyên không có ý nghĩa.
- User tự chọn level hiện tại khi tạo/cập nhật hồ sơ.
- Matching ưu tiên cùng level; mở rộng tối đa một level liền kề khi chờ quá thời gian
  cấu hình.
- Không tự động thay đổi level trong MVP. Nâng level là tính năng riêng sau khi có đủ
  dữ liệu buổi học và rating.

### 3.3. Đăng nhập MVP: số điện thoại/password và Google

Hệ thống hỗ trợ đồng thời:

1. Số điện thoại + password.
2. Google OAuth/OpenID Connect.

Quy tắc nhận diện tài khoản:

- `phoneNumber` là duy nhất khi có giá trị nhưng không bắt buộc với tài khoản chỉ dùng Google.
- Password phải được lưu dưới dạng hash, không lưu plain text.
- Tài khoản Google được nhận diện bằng cặp `(provider, providerId)` duy nhất.
- Email Google đã xác minh có thể dùng để gợi ý liên kết tài khoản, nhưng hệ thống không
  tự động merge hai tài khoản chỉ dựa vào email/phone nếu chưa có bước xác nhận an toàn.
- Một User có thể có nhiều phương thức đăng nhập.
- MVP có hai role: `USER` và `ADMIN`; tài khoản mới mặc định là `USER`.
- Phone OTP nằm ngoài phạm vi vòng đầu. Nếu chưa có OTP provider, `isPhoneVerified`
  phải giữ `false` và không được giả lập là đã xác minh trong production.

Ghi chú dependency:

- `build.gradle` hiện chưa có Spring Security/OAuth2 Client. Việc thêm dependency phải
  nằm trong spec triển khai authentication riêng và được review trước khi code.

### 3.4. Định nghĩa một buổi học hoàn tất

Một `LearningSession` được coi là `COMPLETED` khi thỏa tất cả điều kiện bắt buộc:

1. Session đã từng chuyển sang `IN_PROGRESS` sau khi cả hai user join đúng Agora channel.
2. Cả hai user có khoảng thời gian hiện diện chồng lấp tối thiểu **5 phút**.
3. Session kết thúc bằng một trong các sự kiện hợp lệ:
   - Cả hai user chọn Leave/End;
   - Một user rời phòng và không quay lại trong thời gian grace period **60 giây**;
   - Session đạt thời lượng tối đa do hệ thống cấu hình.
4. Backend ghi được `startedAt`, `endedAt` và `completionReason`.

Rating **không phải** điều kiện để session hoàn tất. Rating là bước hậu buổi học và có thể
được gửi sau.

Các trường hợp không hoàn tất:

- Chỉ một user join.
- Không có khoảng thời gian hai user cùng hiện diện.
- Thời gian cùng hiện diện dưới 5 phút.
- Lỗi kỹ thuật xảy ra trước ngưỡng 5 phút.
- Session bị admin/system hủy.

Trạng thái đề xuất:

- `WAITING`: đang chờ ghép cặp.
- `MATCHED`: đã ghép cặp và cấp channel nhưng chưa đủ hai người join.
- `IN_PROGRESS`: cả hai người đã join.
- `COMPLETED`: đạt điều kiện hoàn tất.
- `INCOMPLETE`: đã bắt đầu nhưng không đạt ngưỡng hoàn tất.
- `CANCELLED`: bị hủy trước khi bắt đầu.

`completionReason` đề xuất:

- `BOTH_LEFT`
- `ONE_LEFT_TIMEOUT`
- `MAX_DURATION_REACHED`
- `INSUFFICIENT_DURATION`
- `TECHNICAL_FAILURE`
- `CANCELLED_BY_SYSTEM`

Lý do chọn ngưỡng 5 phút:

- Đủ ngắn cho MVP và phù hợp định hướng nhiệm vụ học ngắn trong tài liệu.
- Ngăn việc join rồi rời ngay nhưng vẫn được quyền rating/tính tiến độ.
- Phải để ở cấu hình nghiệp vụ, không hardcode rải rác trong code.

## 4. Mô hình dữ liệu mục tiêu cho MVP

Đây là định hướng để viết các spec feature tiếp theo, chưa phải yêu cầu sửa entity ngay.

### 4.1. User và SocialAccount

`User` cần tối thiểu:

- `id`
- `username` hoặc `displayName`
- `phoneNumber` (nullable, unique khi có giá trị)
- `passwordHash` (nullable với tài khoản Google-only)
- `currentLevel` (`N5` đến `N1`)
- `trustScore`
- `avatarUrl`
- `isPhoneVerified`
- `role`
- `status`
- `createdAt`, `updatedAt`

`SocialAccount` cần ràng buộc unique `(provider, providerId)`.

### 4.2. Tag

- `TagCategory`: nhóm level, activity hoặc lesson topic.
- `Tag`: một lựa chọn cụ thể trong category.
- Cần quan hệ User-Tag hoặc một request tham gia queue chứa snapshot các tag được chọn.

### 4.3. LearningSession

Tối thiểu cần:

- `id`
- Hai participant
- Agora `channelName` duy nhất, không cho client tự quyết định
- Level/tag snapshot dùng khi matching
- `status`
- `matchedAt`, `startedAt`, `endedAt`
- Thời lượng hiện diện chồng lấp
- `completionReason`

Backend chỉ cấp Agora token nếu user hiện tại là participant của session còn hợp lệ.

### 4.4. PeerRating

- Gắn với đúng một `LearningSession` đã `COMPLETED`.
- Có `rater`, `ratee`, `totalScore`, `createdAt`.
- Có 7 rating detail, mỗi detail gắn rubric và điểm 1-5.
- Unique `(sessionId, raterId, rateeId)` để ngăn chấm lặp.
- Rater và ratee phải là hai participant khác nhau trong session.
- Không cho tự đánh giá chính mình.

## 5. Validation baseline

### Authentication

- Chuẩn hóa phone number trước khi so sánh/lưu.
- Password phải đáp ứng chính sách độ dài tối thiểu trong spec authentication.
- Không trả password hash hoặc OAuth identifier nhạy cảm ra DTO.
- Phân biệt lỗi validation với lỗi authentication nhưng không để lộ tài khoản có tồn tại
  trong luồng đăng nhập công khai.

### Matching và session

- User chỉ có tối đa một queue entry/session hoạt động tại một thời điểm.
- Không ghép user với chính mình.
- Hai user trong session phải khác nhau.
- Channel name sinh ở backend và không chứa dữ liệu nhận dạng trực tiếp.
- Join/leave event phải idempotent để refresh hoặc retry không cộng sai thời lượng.

### Rating

- Đúng 7 tiêu chí đang active tại thời điểm tạo form/rating snapshot.
- Mỗi điểm là số nguyên từ 1 đến 5.
- `totalScore = sum(details)`.
- Chỉ participant của session hoàn tất mới được rating đối tác.

## 6. Hiện trạng source đã xác minh ngày 2026-08-11

Đã có trong source:

- Package gốc `com.example.videocall_marching_language`.
- Entity: `User`, `SocialAccount`, `Tag`, `TagCategory`, `PeerRating`.
- `AgoraTokenService`, `AgoraController`, `WebController`.
- Trang Thymeleaf và JavaScript gọi video cơ bản.
- Bean cấu hình Cloudinary.
- Gradle khai báo Spring Boot `4.1.0`, Java 17, JPA, MVC, Thymeleaf, Agora,
  Cloudinary và MySQL.

Chưa có:

- Repository layer.
- Service interface/ServiceImpl cho business domain.
- DTO.
- Authentication/authorization.
- Quan hệ User-Tag.
- Queue/matchmaking và `LearningSession`.
- Rubric/rating detail.
- Test unit/integration cho nghiệp vụ.

Sai lệch tài liệu cần xử lý:

- `AGENTS.md` mục Current State nói chưa có entity/service/controller nhưng source đã có.
- `spec_agora_token.md` ghi controller ở package `controller`, source thực tế nằm ở
  `controller.user`.
- PDF có nơi mô tả 8 rubric nhưng quyết định MVP hiện tại là 7.
- `PeerRating` hiện chỉ lưu tổng điểm, chưa đủ để lưu 7 tiêu chí.

## 7. Thứ tự spec/implementation sau khi baseline được duyệt

1. `spec_user_authentication.md`: phone/password, Google, role và profile.
2. `spec_tag_management.md`: admin CRUD và user chọn tag/level.
3. `spec_learning_session_matchmaking.md`: queue, matching 1-1 và session lifecycle.
4. Cập nhật `spec_agora_token.md`: token gắn user/session, authorization và validation.
5. Cập nhật `spec_frontend_video_call.md`: không nhập channel/UID thủ công; nhận session
   từ backend.
6. `spec_rubric_peer_rating.md`: 7 rubric, rating detail và trust-score baseline.

Mỗi spec phải được review trước khi code feature tương ứng.

## 8. Acceptance criteria của baseline

- [x] Rubric MVP được chốt là 7 tiêu chí, mỗi tiêu chí 1-5.
- [x] Level được chốt là N5-N1.
- [x] MVP hỗ trợ phone/password và Google.
- [x] Có định nghĩa đo được cho session `COMPLETED`.
- [x] Có validation và ràng buộc dữ liệu nền tảng.
- [x] Có bảng đối chiếu source hiện tại với phần còn thiếu.
- [ ] Người review xác nhận ngưỡng hoàn tất 5 phút và grace period 60 giây.
- [ ] Người review xác nhận loại `SUPPORTIVENESS` khỏi bộ rubric MVP.
- [x] `gradlew test` chạy thành công trong môi trường hiện tại ngày 2026-08-11.

## 9. Các quyết định cần reviewer xác nhận

1. Giữ ngưỡng hai user cùng hiện diện tối thiểu 5 phút?
2. Giữ grace period reconnect là 60 giây?
3. Loại `SUPPORTIVENESS` khỏi 7 rubric chính hay thay thế một tiêu chí khác?
4. Với phone/password vòng đầu, cho đăng nhập khi `isPhoneVerified=false`, hay bắt buộc
   phải tích hợp OTP trước khi cho sử dụng matching?
