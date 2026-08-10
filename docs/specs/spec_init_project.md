# overview (mục tiêu)
- Khởi tạo dự án videocall_marching_language tạo ra các file theo spring boot, sữ dụng hibernate cho các entity đã được đề cập trong AGENTS.md 

# rules (quy tắc nghiệp vụ)
- user entity mapping với table users có các column :
`Long` `id` PK, 
`String` `username`,
`String` `phone_number`, 
`Boolean` `is_phone_verified`, 
`int` `current_level`, 
`float` `trust_score`, 
`String` `avatar_url` Cloudinary link
- social_account entity mapping với table social_accounts có các column:
`Long` `id` PK, 
`User` `user` (FK trỏ về users), 
`String` `provider` ("GOOGLE", "FACEBOOK", "LINE", "APPLE"), 
`String` `provider_id`

- tag entity mapping với table tags có các column :
`Long` `id` PK, 
`String` 
`category_id`, 
`String` 
`name`
- tag_category entity mapping với table tag_categories có các column :
`Long` `id` PK, 
`String` `name`
- peer_rating entity mapping với table peer_ratings có các column:
`Long` `id` PK
`User` `rater` (FK trỏ về users - Người chấm)
`User` `ratee` (FK trỏ về users - Người bị chấm)
`int` `total_score` (Điểm tổng)
`Datetime` `created_at`

# technical design (thiết kế kĩ thuật)
1. **API Endpoint**: 
2. **Controller (`TripController`)**:
3. **Logic xử lý chi tiết**:
4. **Response**: 
# technical design (thiết kế kĩ thuật)
1. **API Endpoint**: 
2. **Controller (`TripController`)**:
3. **Logic xử lý chi tiết**:
4. **Response**: 

# edge cases (trường hợp đặc biệt)

# acceptance criteria (tiêu chí nghiệm thu)

# verification (kiểm tra)
- **Bước 1**: 
- **Bước 2**: 
- **Bước 3**: 
- **Bước 4**: 
- **Bước 5**: 
- **Bước 6**: 
- **Bước 7**: 
- **Bước 8**: 