package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.repository.ITagCategoryRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
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
            IUserRepository userRepository,
            IScriptRepository scriptRepository) {

        return args -> {
            // 1. Tạo Users mẫu nếu chưa có
            if (userRepository.count() == 0) {
                userRepository.saveAll(List.of(
                        User.builder().username("john_tanaka").phoneNumber("0901234567").currentLevel(5).trustScore(4.5f).isPhoneVerified(true).build(),
                        User.builder().username("yamada_taro").phoneNumber("0908765432").currentLevel(5).trustScore(4.8f).isPhoneVerified(true).build(),
                        User.builder().username("nguyen_van_a").phoneNumber("0903334444").currentLevel(4).trustScore(4.9f).isPhoneVerified(true).build()
                ));
            }

            // 2. Tạo Categories và Tags nếu chưa có
            if (tagCategoryRepository.count() == 0) {
                TagCategory levelCat = tagCategoryRepository.save(TagCategory.builder().name("Trình độ (Level)").build());
                TagCategory formatCat = tagCategoryRepository.save(TagCategory.builder().name("Hình thức (Format)").build());
                TagCategory topicCat = tagCategoryRepository.save(TagCategory.builder().name("Chủ đề (Topic)").build());

                tagRepository.saveAll(List.of(
                        Tag.builder().name("N5").tagCategory(levelCat).build(),
                        Tag.builder().name("N4").tagCategory(levelCat).build(),
                        Tag.builder().name("N3").tagCategory(levelCat).build(),
                        Tag.builder().name("Từ vựng (Vocabulary)").tagCategory(formatCat).build(),
                        Tag.builder().name("Đóng vai (Roleplay)").tagCategory(formatCat).build(),
                        Tag.builder().name("Giới thiệu bản thân").tagCategory(topicCat).build(),
                        Tag.builder().name("Mua sắm (Shopping)").tagCategory(topicCat).build(),
                        Tag.builder().name("Du lịch & Hỏi đường").tagCategory(topicCat).build(),
                        Tag.builder().name("Tiếng Anh giao tiếp").tagCategory(topicCat).build()
                ));
            }

            // 3. Tạo Scripts mẫu để test AI Speaking Practice
            if (scriptRepository.count() == 0) {
                Tag tagIntro = tagRepository.findByName("Giới thiệu bản thân").orElse(null);
                Tag tagRoleplay = tagRepository.findByName("Đóng vai (Roleplay)").orElse(null);
                Tag tagShopping = tagRepository.findByName("Mua sắm (Shopping)").orElse(null);
                Tag tagTravel = tagRepository.findByName("Du lịch & Hỏi đường").orElse(null);
                Tag tagEnglish = tagRepository.findByName("Tiếng Anh giao tiếp").orElse(null);

                // Fallback nếu tag null thì lấy tag đầu tiên
                Tag defaultTag = tagIntro != null ? tagIntro : tagRepository.findAll().stream().findFirst().orElse(null);

                if (defaultTag != null) {
                    scriptRepository.saveAll(List.of(
                            Script.builder()
                                    .title("Giới thiệu bản thân cơ bản (Self-introduction)")
                                    .tag(tagIntro != null ? tagIntro : defaultTag)
                                    .language("ja")
                                    .targetDuration(45)
                                    .content("A: 初めまして、私は田中です。ベトナムから来ました。\nB: 初めまして、田中さん。どうぞよろしくお願いします。\nA: こちらこそ、よろしくお願いいたします。")
                                    .build(),

                            Script.builder()
                                    .title("Mua sắm tại cửa hàng tiện lợi (Combini)")
                                    .tag(tagShopping != null ? tagShopping : defaultTag)
                                    .language("ja")
                                    .targetDuration(60)
                                    .content("A: いらっしゃいませ！お弁当を温めますか？\nB: はい、お願いします。\nA: レジ袋はご利用になりますか？\nB: いいえ、大丈夫です。\nA: お会計は500円になります。\nB: PayPayで払います。")
                                    .build(),

                            Script.builder()
                                    .title("Gọi món tại quán ăn (Restaurant Ordering)")
                                    .tag(tagRoleplay != null ? tagRoleplay : defaultTag)
                                    .language("ja")
                                    .targetDuration(60)
                                    .content("A: すみません、注文をお願いします。\nB: はい、何にいたしましょうか？\nA: ラーメン一つとギョーザをお願いします。\nB: かしこまりました。お飲み物はいかがですか？\nA: お水を一杯ください。")
                                    .build(),

                            Script.builder()
                                    .title("Hỏi đường đến ga tàu (Asking for Directions)")
                                    .tag(tagTravel != null ? tagTravel : defaultTag)
                                    .language("ja")
                                    .targetDuration(50)
                                    .content("A: すみません、東京駅はどこですか？\nB: この道をまっすぐ行って、信号を右に曲がってください。\nA: 歩いてどれくらいかかりますか？\nB: だいたい5分くらいですよ。\nA: ありがとうございます。助かりました。")
                                    .build(),

                            Script.builder()
                                    .title("Daily English Greeting & Coffee (Giao tiếp tiếng Anh)")
                                    .tag(tagEnglish != null ? tagEnglish : defaultTag)
                                    .language("en")
                                    .targetDuration(45)
                                    .content("A: Hello! How are you doing today?\nB: Hi! I am doing well, thank you. How about you?\nA: I am pretty good. Are you free this afternoon?\nB: Yes, I am free. Let's grab a coffee together!\nA: That sounds wonderful!")
                                    .build()
                    ));
                }
            }
        };
    }
}
