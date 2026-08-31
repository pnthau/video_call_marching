package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.repository.ITagCategoryRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class DataInitializer {
    private static final List<RubricSeed> RUBRIC_SEEDS = List.of(
            new RubricSeed(RubricCriteria.ACCURACY, "Độ chính xác", "Độ chính xác về từ vựng và ngữ pháp."),
            new RubricSeed(RubricCriteria.FLUENCY, "Độ trôi chảy", "Khả năng nói tự nhiên, ít ngập ngừng."),
            new RubricSeed(RubricCriteria.PRONUNCIATION_INTONATION, "Phát âm và ngữ điệu", "Chất lượng phát âm, trọng âm và ngữ điệu."),
            new RubricSeed(RubricCriteria.STRUCTURE_LOGIC, "Cấu trúc và mạch lạc", "Cấu trúc và tính logic của nội dung trình bày."),
            new RubricSeed(RubricCriteria.CONTENT_INTERESTINGNESS, "Nội dung thú vị", "Chất lượng và mức độ thú vị của nội dung."),
            new RubricSeed(RubricCriteria.BODY_LANGUAGE, "Ngôn ngữ cơ thể", "Giao tiếp mắt, biểu cảm và ngôn ngữ cơ thể."),
            new RubricSeed(RubricCriteria.ENTHUSIASM_CONFIDENCE, "Sự tự tin và nhiệt tình", "Mức độ tự tin và nhiệt tình khi giao tiếp."));

    @Bean
    public CommandLineRunner initData(ITagCategoryRepository categoryRepository,
                                      ITagRepository tagRepository,
                                      IRubricRepository rubricRepository) {
        return args -> {
            if (categoryRepository.count() == 0) {
                TagCategory level = categoryRepository.save(TagCategory.builder()
                        .name("Trình độ luyện tập").type(TagCategoryType.LEVEL).displayOrder(1).build());
                TagCategory activity = categoryRepository.save(TagCategory.builder()
                        .name("Hình thức học").type(TagCategoryType.ACTIVITY).displayOrder(2).build());
                TagCategory topic = categoryRepository.save(TagCategory.builder()
                        .name("Chủ đề bài học").type(TagCategoryType.TOPIC).displayOrder(3).build());
                tagRepository.saveAll(List.of(
                        Tag.builder().name("N5").tagCategory(level).build(),
                        Tag.builder().name("N4").tagCategory(level).build(),
                        Tag.builder().name("Từ vựng (Vocabulary)").tagCategory(activity).build(),
                        Tag.builder().name("Đóng vai (Roleplay)").tagCategory(activity).build(),
                        Tag.builder().name("Giới thiệu bản thân").tagCategory(topic).build()));
            }
            validateRubricCriteria(rubricRepository.findAllCriteriaCodes());
            RUBRIC_SEEDS.forEach(seed -> seedIfMissing(rubricRepository, seed));
            validateCompleteRubricSet(rubricRepository.findAllCriteriaCodes());
        };
    }

    private void seedIfMissing(IRubricRepository repository, RubricSeed seed) {
        if (repository.findByCriteria(seed.criteria()).isEmpty()) {
            repository.save(Rubric.builder().criteria(seed.criteria()).displayName(seed.displayName())
                    .description(seed.description()).active(true).build());
        }
    }

    private void validateRubricCriteria(List<String> criteriaCodes) {
        Set<String> approvedCodes = Arrays.stream(RubricCriteria.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> unknownCodes = criteriaCodes.stream()
                .filter(code -> !approvedCodes.contains(code))
                .collect(Collectors.toSet());
        if (!unknownCodes.isEmpty()) {
            throw new IllegalStateException("Unknown rubric criteria found: " + unknownCodes);
        }
    }

    private void validateCompleteRubricSet(List<String> criteriaCodes) {
        validateRubricCriteria(criteriaCodes);
        Set<RubricCriteria> actualCriteria = criteriaCodes.stream()
                .map(RubricCriteria::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RubricCriteria.class)));
        Set<RubricCriteria> expectedCriteria = EnumSet.allOf(RubricCriteria.class);
        if (criteriaCodes.size() != expectedCriteria.size() || !actualCriteria.equals(expectedCriteria)) {
            throw new IllegalStateException("Rubric criteria invariant violation: expected exactly "
                    + expectedCriteria + " but found " + criteriaCodes);
        }
    }

    private record RubricSeed(RubricCriteria criteria, String displayName, String description) {
    }
}
