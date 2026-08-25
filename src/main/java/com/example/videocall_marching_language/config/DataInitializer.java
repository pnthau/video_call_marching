package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.repository.ITagCategoryRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(
            ITagCategoryRepository tagCategoryRepository,
            ITagRepository tagRepository,
            IRubricRepository rubricRepository) {

        return args -> {
            // ── TAGS ─────────────────────────────────────────────────────────
            // Kiểm tra nếu DB chưa có dữ liệu mới khởi tạo (tránh trùng lặp khi restart)
            if (tagCategoryRepository.count() == 0) {

                // 1. Tạo Categories
                TagCategory levelCat = tagCategoryRepository.save(TagCategory.builder()
                        .name("Trình độ luyện tập").type(TagCategoryType.LEVEL).displayOrder(1).build());
                TagCategory formatCat = tagCategoryRepository.save(TagCategory.builder()
                        .name("Hình thức học").type(TagCategoryType.ACTIVITY).displayOrder(2).build());
                TagCategory topicCat = tagCategoryRepository.save(TagCategory.builder()
                        .name("Chủ đề bài học").type(TagCategoryType.TOPIC).displayOrder(3).build());

                // 2. Tạo Tags
                tagRepository.saveAll(List.of(
                        Tag.builder().name("N5").tagCategory(levelCat).build(),
                        Tag.builder().name("N4").tagCategory(levelCat).build(),
                        Tag.builder().name("Từ vựng (Vocabulary)").tagCategory(formatCat).build(),
                        Tag.builder().name("Đóng vai (Roleplay)").tagCategory(formatCat).build(),
                        Tag.builder().name("Giới thiệu bản thân").tagCategory(topicCat).build()
                ));
            }

            // ── RUBRICS ──────────────────────────────────────────────────────
            // Seed 7 rubric cố định nếu bảng trống — idempotent
            if (rubricRepository.count() == 0) {
                rubricRepository.saveAll(List.of(
                        Rubric.builder()
                                .criteria(RubricCriteria.ACCURACY)
                                .displayName("Độ chính xác (Từ vựng & Ngữ pháp)")
                                .description("Đánh giá mức độ chính xác về từ vựng và ngữ pháp trong câu nói.")
                                .isActive(true)
                                .build(),
                        Rubric.builder()
                                .criteria(RubricCriteria.FLUENCY)
                                .displayName("Độ trôi chảy")
                                .description("Mức độ trôi chảy, ít ngập ngừng hay vấp váp khi nói.")
                                .isActive(true)
                                .build(),
                        Rubric.builder()
                                .criteria(RubricCriteria.PRONUNCIATION_INTONATION)
                                .displayName("Phát âm & Trọng âm")
                                .description("Chất lượng phát âm và ngữ điệu, trọng âm câu.")
                                .isActive(true)
                                .build(),
                        Rubric.builder()
                                .criteria(RubricCriteria.STRUCTURE_LOGIC)
                                .displayName("Cấu trúc & Mạch lạc")
                                .description("Tính mạch lạc, có cấu trúc và logic trong nội dung trình bày.")
                                .isActive(true)
                                .build(),
                        Rubric.builder()
                                .criteria(RubricCriteria.CONTENT_INTERESTINGNESS)
                                .displayName("Chất lượng & Thú vị của nội dung")
                                .description("Mức độ thú vị, phong phú và chất lượng của nội dung chia sẻ.")
                                .isActive(true)
                                .build(),
                        Rubric.builder()
                                .criteria(RubricCriteria.BODY_LANGUAGE)
                                .displayName("Ngôn ngữ cơ thể")
                                .description("Giao tiếp mắt, biểu cảm khuôn mặt và ngôn ngữ cơ thể phù hợp.")
                                .isActive(true)
                                .build(),
                        Rubric.builder()
                                .criteria(RubricCriteria.ENTHUSIASM_CONFIDENCE)
                                .displayName("Tự tin & Hào hứng")
                                .description("Mức độ tự tin và hào hứng thể hiện trong suốt cuộc trò chuyện.")
                                .isActive(true)
                                .build()
                ));
            }
        };
    }
}

