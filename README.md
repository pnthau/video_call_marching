# Videocall Marching Language 🎥🗣️

## 1. Giới thiệu ứng dụng
**Videocall Marching Language** là một hệ thống ứng dụng công nghệ trực tuyến giúp người học ngôn ngữ (đặc biệt là tiếng Nhật/Anh) luyện tập giao tiếp tương tác qua các cuộc gọi video 1-kèm-1 (Peer-to-Peer).
Mục đích chính của ứng dụng là tạo ra một môi trường thực tế để người dùng có thể áp dụng ngôn ngữ đang học vào việc nói theo các chủ đề cụ thể, từ đó nâng cao phản xạ giao tiếp tự nhiên thay vì chỉ học sách vở.

## 2. Công Nghệ & Môi Trường Phát Triển (Tech Stack)
Dự án được xây dựng dựa trên các công nghệ và bộ thư viện hiện đại:
- **Ngôn ngữ**: Java 17
- **Framework Core**: Spring Boot 4.1.0
- **Database & ORM**: MySQL 8.x, Spring Data JPA, Hibernate
- **Frontend/Giao diện**: Thymeleaf, Bootstrap 5, Vanilla JS
- **Dịch vụ tích hợp (3rd Party)**:
  - **Agora RTC**: Cung cấp nền tảng và SDK truyền phát Video/Audio thời gian thực.
  - **Cloudinary**: Dịch vụ quản lý, lưu trữ và tối ưu hoá hình ảnh (Avatar người dùng, tài liệu media).
- **Công cụ hỗ trợ**: Lombok, Gradle, dotenv (quản lý biến môi trường).

## 3. Cấu Hình Dự Án

### 3.1. Thiết lập biến môi trường (.env)
Dự án sử dụng file `.env` để bảo mật thông tin cấu hình nhạy cảm. Bạn cần tạo một file tên là `.env` ở thư mục gốc của dự án (cùng cấp với thư mục `src`) và điền các thông tin sau:

```env
# MySQL Database
MYSQL_USERNAME=root
MYSQL_PASSWORD=mật_khẩu_mysql_của_bạn

# Cloudinary Config
CLOUDINARY_CLOUD_NAME=tên_cloud_của_bạn
CLOUDINARY_API_KEY=api_key_của_bạn
CLOUDINARY_API_SECRET=api_secret_của_bạn

# Agora Video Call
AGORA_APP_ID=app_id_của_bạn
AGORA_APP_CERTIFICATE=certificate_của_bạn
```

### 3.2. Cấu hình Database
Đảm bảo bạn đã cài đặt MySQL Server và tạo database tương ứng trước khi chạy ứng dụng:
```sql
CREATE DATABASE videocall_marching_language CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
*(Spring Boot được cấu hình `ddl-auto=update` sẽ tự động tạo bảng (tables) dựa trên các Entities khi khởi động ứng dụng).*

## 4. Hướng Dẫn Import & Thiết Lập Dự Án trên IntelliJ IDEA

Để bắt đầu làm việc với dự án, hãy thực hiện các bước sau trên IntelliJ IDEA:

1. **Mở dự án**: Mở IntelliJ IDEA ➔ Chọn **File > Open** (hoặc **Open** ở màn hình Welcome) ➔ Trỏ đến thư mục chứa source code (`video_marching`) và nhấn **OK**.
2. **Tải thư viện Gradle**: IntelliJ sẽ tự động nhận diện đây là dự án Gradle và tải các thư viện (dependencies) về máy. Nếu không tự động, mở tab Gradle ở bên phải và nhấn nút **Reload All Gradle Projects**.
3. **Cấu hình JDK**: Vào **File > Project Structure (Ctrl+Alt+Shift+S)** ➔ Trong mục *Project*, chọn **SDK** là `Java 17`.
4. **Cài đặt Lombok**: Vào **File > Settings > Plugins**, tìm và cài đặt plugin **Lombok**. Tiếp theo, vào **Settings > Build, Execution, Deployment > Compiler > Annotation Processors** và tích chọn **Enable annotation processing**.
5. **Chạy ứng dụng**: Mở file `VideocallMarchingLanguageApplication.java` (trong thư mục `src/main/java/.../`) và click nút Run xanh lá, hoặc chạy task `bootRun` từ bảng Gradle.

## 5. Nguyên Tắc Làm Việc với Git

### Quy trình làm việc 6 bước của một Developer hàng ngày:

- **Bước 1 (Cập nhật)**: Lấy code mới nhất của đồng đội về máy mình trước khi viết code mới:
  ```bash
  git checkout master
  git pull origin master
  ```

- **Bước 2 (Tạo nhánh)**: Tạo nhánh riêng cho tính năng của mình:
  ```bash
  git checkout -b feature/quick-order
  ```

- **Bước 3 (Code & Commit)**: Thực hiện viết code, sau đó lưu lại (commit) trên máy cá nhân:
  ```bash
  git add .
  git commit -m "feat: implement quick order form and web.xml routing"
  ```

- **Bước 4 (Push)**: Đẩy nhánh tính năng lên GitHub:
  ```bash
  git push origin feature/quick-order
  ```

- **Bước 5 (Tạo Pull Request - PR)**: Lên giao diện GitHub trên web, anh sẽ thấy nút **Compare & pull request**. Nhấp vào đó, ghi mô tả những gì anh đã làm và gửi đi.

- **Bước 6 (Review & Merge)**: Đồng đội sẽ vào xem code của anh, nhận xét (nếu có) và nhấn **Approve**. Sau đó, code sẽ được gộp (Merge) vào nhánh `master` chính thức trên GitHub.

---

### ⚠️ Lưu ý quan trọng khi bị xung đột code (Merge Conflict)

Nếu anh và đồng đội cùng sửa trên một dòng của một file, Git sẽ báo Conflict và không cho gộp tự động. Đừng lo lắng, hãy xử lý theo quy trình 3 bước an toàn sau:

1. **Lấy code master mới nhất về máy mình**:
   `git checkout master` ➔ `git pull origin master`
2. **Quay lại nhánh của mình và gộp master vào**:
   `git checkout feature/quick-order` ➔ `git merge master`
3. **Xử lý xung đột trên IDE**: Mở file bị báo đỏ trên IntelliJ, IDE sẽ hiện công cụ giải quyết xung đột trực quan (Merge Tool). Anh chỉ cần chọn giữ lại code của anh, code của đồng đội, hoặc trộn cả hai. Sau đó **Save** ➔ **Commit** ➔ **Push** lại nhánh tính năng lên GitHub. Pull Request sẽ tự động chuyển sang trạng thái xanh sạch để gộp!
