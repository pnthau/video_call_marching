package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.enums.TagCategoryType;
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
            ITagRepository tagRepository) {

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
        };
    }
}
