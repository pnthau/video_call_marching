# Feature Specification: Luyện nói 3 Giai đoạn với AI Hướng dẫn

## 1. Tổng quan (Overview)
Tính năng luyện nói (Speaking Practice) giúp người dùng học cách phát âm và trình bày một chủ đề theo 3 cấp độ khó tăng dần. Tính năng này ứng dụng AI (Gemini API) để phân tích, sửa lỗi phát âm/ngữ pháp và hướng dẫn người dùng trong thời gian thực. Việc thăng cấp giữa các giai đoạn được đánh giá dựa trên **thời gian hoàn thành** của người học.

## 2. Chi tiết 3 Giai đoạn (Phases)
- **Giai đoạn 1 (Beginner): Nghe & Lặp lại từng câu (Listen & Repeat)**
  - Chỉ hiển thị từng câu script một (không hiển thị toàn bộ bài cùng lúc).
  - **Luồng hoạt động:** Hệ thống (AI/Text-to-Speech) sẽ đọc mẫu 1 câu -> Người dùng đọc lặp lại câu đó -> AI phân tích kiểm tra và sửa lỗi ngay lập tức. Sau khi hoàn thành hoặc sửa đúng mới chuyển sang câu tiếp theo.
  - Mục đích: Giúp người dùng làm quen với từ vựng, ngữ điệu, và phát âm chuẩn từng câu một cách kỹ lưỡng.

- **Giai đoạn 2 (Intermediate): Script khuyết từ khóa (Fill-in-the-blanks)**
  - Hệ thống tự động chọn lọc và che đi những từ quan trọng (ví dụ: động từ chính, danh từ, từ vựng trọng tâm) trong script, thay vì chỉ che ngẫu nhiên các từ đơn giản.
  - Người dùng phải vừa nhớ vừa phát âm đúng những từ bị khuyết để hoàn thành câu.
  - Mục đích: Kích thích khả năng ghi nhớ và phản xạ ngôn ngữ.

- **Giai đoạn 3 (Advanced): Ẩn hoàn toàn script**
  - Không hiển thị bất kỳ văn bản nào, người dùng phải tự nói hoàn chỉnh câu script của mình.
  - Mục đích: Đạt mức độ giao tiếp lưu loát và tự nhiên thực tế.

## 3. Tích hợp AI Hướng dẫn (AI Guidance)
- **Cơ chế hoạt động:**
  - AI đọc mẫu (Giai đoạn 1): Có thể sử dụng thư viện Text-to-Speech (TTS) tích hợp sẵn ở trình duyệt (Frontend) hoặc API Audio để tiết kiệm chi phí.
  - Nhận diện giọng nói (STT): Lời nói của người dùng sẽ được chuyển đổi thành văn bản từ Frontend hoặc Backend.
  - Kiểm tra lỗi: Gửi văn bản STT đến **Gemini API** qua giao thức **HTTP thuần** (không dùng SDK ngoài).
- **Phản hồi của AI:**
  - Phân tích câu nói của người dùng so với câu gốc trong script.
  - Nếu phát hiện lỗi sai, AI sẽ trả về câu/từ sai và hướng dẫn người dùng sửa lại.
  - *Lưu ý kỹ thuật:* Bắt buộc cấu hình fallback (trả về mock response) khi `GEMINI_API_KEY` trống hoặc lỗi kết nối.

## 4. Hệ thống Đánh giá & Chuyển giai đoạn (Evaluation & Progression)
- Hệ thống sẽ ghi nhận **Thời gian hoàn thành (Completion Time)** của người dùng đối với mỗi bài luyện tập (từ lúc bắt đầu đến lúc hoàn thành kịch bản của giai đoạn đó).
- **Tiêu chí chuyển giai đoạn:**
  - Có các mốc thời gian quy định sẵn được lưu trong Entity `Script` (field `targetDuration`).
  - Nếu thời gian hoàn thành ≤ Thời gian mục tiêu: Đủ điều kiện thăng cấp lên Giai đoạn tiếp theo.
  - Nếu thời gian hoàn thành > Thời gian mục tiêu: Cần luyện tập lại Giai đoạn hiện tại để tăng tốc độ phản xạ.

## 5. Đề xuất Dữ liệu & Kiến trúc (Data & Architecture Proposal)

### 5.1 Entities sử dụng & bổ sung:
*Sử dụng Entity `Tag` đã có để quản lý các chủ đề bài học.*

- **`Tag`** (Entity đã có - Phân loại chủ đề)
  - `id`: Long
  - `name`: String
  - `description`: String

- **`Script`** (Nội dung bài nói gốc)
  - `id`: Long
  - `tagId`: Long (Khoá ngoại)
  - `title`: String
  - `content`: Text (Nội dung văn bản đầy đủ. Backend sẽ phụ trách bóc tách/chia câu (split) ở Giai đoạn 1 nếu cần)
  - `language`: String (vd: "EN", "JP")
  - `targetDuration`: Integer (Thời gian mục tiêu, tính bằng giây)

- **`PracticeHistory`** (Lịch sử luyện tập của user)
  - `id`: Long
  - `userId`: Long
  - `scriptId`: Long
  - `phase`: Integer (1, 2, 3)
  - `startTime`: LocalDateTime
  - `endTime`: LocalDateTime
  - `durationInSeconds`: Integer
  - `isPassed`: Boolean
  - `aiFeedback`: Text

### 5.2 API Endpoints dự kiến:
- `GET /api/scripts/by-tag/{tagId}?phase={1,2,3}`: Lấy kịch bản. Ở phase 1, backend có thể trả về mảng các câu (sentences array) để frontend dễ dàng duyệt từng câu. Phase 2 trả về script đã khuyết từ.
- `POST /api/practice/start`: Bắt đầu bấm giờ.
- `POST /api/practice/check`: Gửi đoạn text (STT) + câu gốc để Gemini kiểm tra và trả về hướng dẫn (sai/đúng, điểm cần sửa).
- `POST /api/practice/finish`: Kết thúc bài, lưu thời gian, check logic qua màn.

## 6. Kế hoạch Triển khai (Implementation Steps)
1. **Database & Entity:** Tạo `Script`, `PracticeHistory` (map với `Tag`).
2. **Core Logic:** Viết hàm chia câu cho Giai đoạn 1; thuật toán phân tích và che các từ khóa quan trọng (masking keyword) cho Giai đoạn 2 (có thể sử dụng NLP cơ bản hoặc gọi LLM để trích xuất từ khóa trước khi hiển thị).
3. **AI Integration:** 
   - Viết WebClient/RestTemplate gọi **Gemini API**.
   - Thiết kế Prompt so sánh câu nói người dùng với câu chuẩn.
4. **Time Logic:** Tính `durationInSeconds` và check `isPassed`.
5. **Controller:** API Endpoints.

---
> **Trạng thái Document:** Cần User review và xác nhận (Approve).
