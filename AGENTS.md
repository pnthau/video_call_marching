# Project Context -- videocall_marching_language

> File này là ngữ cảnh chung (Memory Bank) của dự án theo mô hình Spec-Driven Development --
> phân biệt rõ **Trạng thái hiện tại** (đã verify trong code) và **Kiến trúc mục tiêu** (định hướng, chưa triển khai).
> AI Agent PHẢI đọc mục 2 trước khi sửa bất kỳ file nào -- không suy đoán cấu trúc từ mục 3.

## 1. Project Overview
- videocall_marching_language: Là hệ thống giúp học tương tác giữa user với user nhằm đảm bảo quá trình output của user.
- Mục đích: giúp người dùng sữ dụng ngôn ngữ mình học ứng dụng vào việc nói thực tế các chủ đề -- nâng cao khả năng sữ dụng ngôn ngữ thay vì học ngôn ngữ đơn thuần
- Ưu tiên: code sạch, dễ mở rộng; mọi thay đổi từ AI phải bám sát Spec trước khi code

## 2. Trạng thái hiện tại (Current State) -- đã xác minh trong code
- Package gốc thực tế: `com.example.videocall_marching_language` 
- Entity hiện có gồm `User`, `SocialAccount`, `Tag`, `TagCategory`, `PeerRating`, `LearningSession` và `SessionPresence`.
- Cấu trúc hiện tại đã có `service/`, `service/impl/`, `dto/`, `repository/`, `controller/` và `entity/`.
- Authentication dùng Spring Security/Google OIDC; Lifecycle V2 dùng persistent presence, authenticated matchmaking, Agora token theo session, scheduler/finalizer và WebSocket recovery.
- Lombok đang dùng `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` (không dùng `@Data`)
- `application.properties` (profile mặc định) trỏ MySQL/Cloudinary, chạy Flyway V1-V3 và dùng Hibernate `ddl-auto=validate`.

## 3. Kiến trúc mục tiêu (Target Architecture) -- CHƯA triển khai, chỉ dùng làm định hướng khi refactor
- Package chuẩn hoá: `com.codegym.videocall_marching_language`
- Entity mở rộng: 
- Pattern bắt buộc khi refactor: `Repository → Service → ServiceImpl → Controller`
- Không trả Entity trực tiếp ra Controller -- luôn map qua DTO
-- **Quy tắc cho AI Agent**: mặc định code theo đúng cấu trúc HIỆN TẠI (mục 2). Chỉ áp dụng Kiến trúc mục tiêu (mục 3) khi Spec của task đó yêu cầu rõ ràng "refactor sang kiến trúc mục tiêu". Không tự ý đổi tên package, tự tạo Service layer,

## 4. Coding Conventions & Standards
- Stack: Spring Boot 4.0.6, Java 17, Lombok, Spring Data JPA, cloudinary
- Naming: camelCase cho field/method, PascalCase cho class
- DB: MySQL (mặc định)
- Constructor injection (qua `@RequiredArgsConstructor` hoặc constructor thường), không dùng `@Autowired` field injection

## 5. Response Style
- Trả về code Java hoàn chỉnh, copy-paste được ngay
- Không thêm dependency ngoài `build.gradle` hiện tại
- Không tự ý sửa file config (`application.properties`...) trừ khi được yêu cầu trực tiếp
- Review code: dùng format có cấu trúc, không viết đoạn văn dài

## 6. Workflows & Modes (theo mô hình Spec-Driven Development -- Explore → Plan → Code → Commit)
- **Explore**: đọc `@file` liên quan để hiểu context hiện tại, không suy đoán nội dung -- đặc biệt lưu ý mục 2 và mục 3 ở trên KHÁC NHAU
- **Plan**: task phức tạp (feature mới, refactor nhiều file) → viết Spec riêng trong `docs/specs/`, chờ người review xác nhận mới code. Task đơn giản (fix 1 bug, sửa 1 method) → code ngay không cần Spec
- **Code**: AI đọc Spec đã chốt, sửa đúng phạm vi được giao, không tự ý mở rộng
- **Commit**: chạy `./gradlew build` hoặc `./gradlew test` để verify trước khi coi task hoàn thành

## 7. Module-specific Rules (áp dụng cho code hiện tại -- mục 2)

### AI Features (Kiến trúc mục tiêu -- chưa có trong code hiện tại)
- Dùng Claude API (`claude-sonnet-4-6`) qua HTTP thuần, không thêm SDK ngoài
- Bắt buộc fallback mock response khi `CLAUDE_API_KEY` rỗng hoặc lỗi

## 8. Session Management
Cuối mỗi session, tự động tạo summary với format:
- Đang làm gì?
- Đã xong gì?
- Decision đã chốt
- Task tiếp theo
