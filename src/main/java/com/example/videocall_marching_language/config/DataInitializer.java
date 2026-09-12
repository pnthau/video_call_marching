package com.example.videocall_marching_language.config;

import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.entity.Script;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.RubricCriteria;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IRubricRepository;
import com.example.videocall_marching_language.repository.IScriptRepository;
import com.example.videocall_marching_language.repository.ITagCategoryRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.repository.IUserRepository;
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
                        Tag.builder().name("N3").tagCategory(level).build(),
                        Tag.builder().name("N2").tagCategory(level).build(),
                        Tag.builder().name("N1").tagCategory(level).build(),
                        Tag.builder().name("A1").tagCategory(level).build(),
                        Tag.builder().name("A2").tagCategory(level).build(),
                        Tag.builder().name("B1").tagCategory(level).build(),
                        Tag.builder().name("B2").tagCategory(level).build(),
                        Tag.builder().name("C1").tagCategory(level).build(),
                        Tag.builder().name("Từ vựng (Vocabulary)").tagCategory(activity).build(),
                        Tag.builder().name("Đóng vai (Roleplay)").tagCategory(activity).build(),
                        Tag.builder().name("Hội thoại tự do (Free Talk)").tagCategory(activity).build(),
                        Tag.builder().name("Giới thiệu bản thân").tagCategory(topic).build(),
                        Tag.builder().name("Mua sắm (Shopping)").tagCategory(topic).build(),
                        Tag.builder().name("Du lịch & Hỏi đường").tagCategory(topic).build(),
                        Tag.builder().name("Tiếng Anh giao tiếp").tagCategory(topic).build()));
            } else {
                List<TagCategory> cats = categoryRepository.findByActiveTrueOrderByDisplayOrderAsc();
                TagCategory levelCat = cats.stream().filter(c -> c.getType() == TagCategoryType.LEVEL).findFirst().orElse(null);
                if (levelCat != null) {
                    List<String> levelsToSeed = List.of("N5", "N4", "N3", "N2", "N1", "A1", "A2", "B1", "B2", "C1");
                    for (String lvl : levelsToSeed) {
                        if (tagRepository.findByName(lvl).isEmpty()) {
                            tagRepository.save(Tag.builder().name(lvl).tagCategory(levelCat).build());
                        }
                    }
                }
                TagCategory actCat = cats.stream().filter(c -> c.getType() == TagCategoryType.ACTIVITY).findFirst().orElse(null);
                if (actCat != null && tagRepository.findByName("Hội thoại tự do (Free Talk)").isEmpty()) {
                    tagRepository.save(Tag.builder().name("Hội thoại tự do (Free Talk)").tagCategory(actCat).build());
                }
            }

            validateRubricCriteria(rubricRepository.findAllCriteriaCodes());
            RUBRIC_SEEDS.forEach(seed -> seedIfMissing(rubricRepository, seed));
            validateCompleteRubricSet(rubricRepository.findAllCriteriaCodes());
        };
    }

    @Bean
    public CommandLineRunner initPracticeScriptsData(IUserRepository userRepository,
                                                     IScriptRepository scriptRepository,
                                                     ITagRepository tagRepository) {
        return args -> {
            // 1. Tạo Users mẫu nếu chưa có
            if (userRepository.count() == 0) {
                userRepository.saveAll(List.of(
                        User.builder().username("john_tanaka").email("john_tanaka@example.com").currentLevel(JapaneseLevel.N5).trustScore(4.5f).build(),
                        User.builder().username("yamada_taro").email("yamada_taro@example.com").currentLevel(JapaneseLevel.N5).trustScore(4.8f).build(),
                        User.builder().username("nguyen_van_a").email("nguyen_van_a@example.com").currentLevel(JapaneseLevel.N4).trustScore(4.9f).build()
                ));
            }

            // 2. Tạo Scripts mẫu để test AI Speaking Practice
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
                                    .level("N5")
                                    .targetDuration(45)
                                    .content("A: 初めまして、私は田中です。ベトナムから来ました。\nB: 初めまして、田中さん。どうぞよろしくお願いします。\nA: こちらこそ、よろしくお願いいたします。")
                                    .phoneticContent("A: はじめまして、わたしはたなかです。べとなむからきました。\nB: はじめまして、たなかさん。どうぞよろしくおねがいします。\nA: こちらこそ、よろしくおねがいいたします。")
                                    .meaningContent("A: Rất vui được gặp bạn, tôi là Tanaka. Tôi đến từ Việt Nam.\nB: Rất vui được gặp bạn, anh Tanaka. Rất mong được giúp đỡ.\nA: Chính tôi mới là người mong được giúp đỡ.")
                                    .build(),

                            Script.builder()
                                    .title("Mua sắm tại cửa hàng tiện lợi (Combini)")
                                    .tag(tagShopping != null ? tagShopping : defaultTag)
                                    .language("ja")
                                    .level("N5")
                                    .targetDuration(60)
                                    .content("A: いらっしゃいませ！お弁当を温めますか？\nB: はい、お願いします。\nA: レジ袋はご利用になりますか？\nB: いいえ、大丈夫です。\nA: お会計は500円になります。\nB: PayPayで払います。")
                                    .phoneticContent("A: いらっしゃいませ！おべんとうをあたためますか？\nB: はい、おねがいします。\nA: レジぶくろはごりようになりますか？\nB: いいえ、だいじょうぶです。\nA: おかいけいはごひゃくえんになります。\nB: ペイペイではらいます。")
                                    .meaningContent("A: Xin chào quý khách! Quý khách có muốn hâm nóng hộp cơm bento không?\nB: Vâng, xin vui lòng hâm giúp tôi.\nA: Quý khách có dùng túi nilon không ạ?\nB: Không, tôi không cần túi đâu.\nA: Tổng tiền thanh toán là 500 yên.\nB: Tôi sẽ thanh toán bằng PayPay.")
                                    .build(),

                            Script.builder()
                                    .title("Gọi món tại quán ăn (Restaurant Ordering)")
                                    .tag(tagRoleplay != null ? tagRoleplay : defaultTag)
                                    .language("ja")
                                    .level("N4")
                                    .targetDuration(60)
                                    .content("A: すみません、注文をお願いします。\nB: はい、何にいたしましょうか？\nA: ラーメン一つとギョーザをお願いします。\nB: かしこまりました。お飲み物はいかがですか？\nA: お水を一杯ください。")
                                    .phoneticContent("A: すみません、ちゅうもんをおねがいします。\nB: はい、なににいたしましょうか？\nA: らーめんひとつとぎょーざをおねがいします。\nB: かしこまりました。お飲み物はいかがですか？\nA: おみずをいっぱいください。")
                                    .meaningContent("A: Xin lỗi, cho tôi gọi món với.\nB: Vâng, quý khách muốn dùng món gì ạ?\nA: Cho tôi một bát ramen và một đĩa gyoza.\nB: Tôi đã hiểu. Quý khách có muốn gọi đồ uống gì không?\nA: Cho tôi một ly nước lọc.")
                                    .build(),

                            Script.builder()
                                    .title("Hỏi đường đến ga tàu (Asking for Directions)")
                                    .tag(tagTravel != null ? tagTravel : defaultTag)
                                    .language("ja")
                                    .level("N4")
                                    .targetDuration(50)
                                    .content("A: すみません、東京駅はどこですか？\nB: この道をまっすぐ行って、信号を右に曲がってください。\nA: 歩いてどれくらいかかりますか？\nB: だいたい5分くらいですよ。\nA: ありがとうございます。助かりました。")
                                    .phoneticContent("A: すみません、とうきょうえきはどこですか？\nB: このみちをまっすぐいって、しんごうをみぎにまがってください。\nA: あるいてどれくらいかかりますか？\nB: だいたいごふんくらいですよ。\nA: ありがとうございます。たすかりました。")
                                    .meaningContent("A: Xin lỗi, ga Tokyo ở đâu vậy ạ?\nB: Bạn đi thẳng con đường này, rồi rẽ phải ở cột đèn giao thông nhé.\nA: Đi bộ mất khoảng bao lâu vậy ạ?\nB: Tầm khoảng 5 phút thôi bạn.\nA: Xin cảm ơn bạn rất nhiều. May quá.")
                                    .build(),

                            Script.builder()
                                    .title("Daily English Greeting & Coffee (Giao tiếp tiếng Anh)")
                                    .tag(tagEnglish != null ? tagEnglish : defaultTag)
                                    .language("en")
                                    .level("A1")
                                    .targetDuration(45)
                                    .content("A: Hello! How are you doing today?\nB: Hi! I am doing well, thank you. How about you?\nA: I am pretty good. Are you free this afternoon?\nB: Yes, I am free. Let's grab a coffee together!\nA: That sounds wonderful!")
                                    .phoneticContent("A: /həˈloʊ! haʊ ɑːr juː ˈduːɪŋ təˈdeɪ?/\nB: /haɪ! aɪ æm ˈduːɪŋ wɛl, θæŋk juː. haʊ əˈbaʊt juː?/\nA: /aɪ æm ˈprɪti ɡʊd. ɑːr juː friː ðɪs ˌæftərˈnuːn?/\nB: /jɛs, aɪ æm friː. lɛts ɡræb ə ˈkɔːfi təˈɡɛðər!/\nA: /ðæt saʊndz ˈwʌndərfəl!/")
                                    .meaningContent("A: Xin chào! Hôm nay bạn thế nào?\nB: Chào bạn! Tôi khỏe, cảm ơn bạn. Còn bạn thì sao?\nA: Tôi cũng rất ổn. Chiều nay bạn có rảnh không?\nB: Có, tôi rảnh. Cùng đi uống cà phê nhé!\nA: Nghe tuyệt vời đấy!")
                                    .build()
                    ));
                }
            } else {
                // 3b. Nếu scripts đã có trong Database nhưng thiếu phoneticContent hoặc meaningContent -> Tự động bổ sung
                List<Script> existingScripts = scriptRepository.findAll();
                for (Script s : existingScripts) {
                    boolean changed = false;
                    String title = s.getTitle() != null ? s.getTitle() : "";
                    if (s.getPhoneticContent() == null || s.getPhoneticContent().trim().isEmpty()) {
                        if (title.contains("Giới thiệu bản thân")) {
                            s.setPhoneticContent("A: はじめまして、わたしはたなかです。べとなむからきました。\nB: はじめまして、たなかさん。どうぞよろしくおねがいします。\nA: こちらこそ、よろしくおねがいいたします。");
                        } else if (title.contains("Mua sắm") || title.contains("Combini")) {
                            s.setPhoneticContent("A: いらっしゃいませ！おべんとうをあたためますか？\nB: はい、おねがいします。\nA: レジぶくろはごりようになりますか？\nB: いいえ、だいじょうぶです。\nA: おかいけいはごひゃくえんになります。\nB: ペイペイではらいます。");
                        } else if (title.contains("Gọi món") || title.contains("quán ăn")) {
                            s.setPhoneticContent("A: すみません、ちゅうもんをおねがいします。\nB: はい、なににいたしましょうか？\nA: らーめんひとつとぎょーざをおねがいします。\nB: かしこまりました。お飲み物はいかがですか？\nA: おみずをいっぱいください。");
                        } else if (title.contains("Hỏi đường") || title.contains("ga tàu")) {
                            s.setPhoneticContent("A: すみません、とうきょうえきはどこですか？\nB: このみちをまっすぐいって、しんごうをみぎにまがってください。\nA: あるいてどれくらいかかりますか？\nB: だいたいごふんくらいですよ。\nA: ありがとうございます。たすかりました。");
                        } else if (title.contains("English") || title.contains("Coffee") || "en".equalsIgnoreCase(s.getLanguage())) {
                            s.setPhoneticContent("A: /həˈloʊ! haʊ ɑːr juː ˈduːɪŋ təˈdeɪ?/\nB: /haɪ! aɪ æm ˈduːɪŋ wɛl, θæŋk juː. haʊ əˈbaʊt juː?/\nA: /aɪ æm ˈprɪti ɡʊd. ɑːr juː friː ðɪs ˌæftərˈnuːn?/\nB: /jɛs, aɪ æm friː. lɛts ɡræb ə ˈkɔːfi təˈɡɛðər!/\nA: /ðæt saʊndz ˈwʌndərfəl!/");
                        } else {
                            s.setPhoneticContent(s.getContent());
                        }
                        changed = true;
                    }
                    if (s.getMeaningContent() == null || s.getMeaningContent().trim().isEmpty()) {
                        if (title.contains("Giới thiệu bản thân")) {
                            s.setMeaningContent("A: Rất vui được gặp bạn, tôi là Tanaka. Tôi đến từ Việt Nam.\nB: Rất vui được gặp bạn, anh Tanaka. Rất mong được giúp đỡ.\nA: Chính tôi mới là người mong được giúp đỡ.");
                        } else if (title.contains("Mua sắm") || title.contains("Combini")) {
                            s.setMeaningContent("A: Xin chào quý khách! Quý khách có muốn hâm nóng hộp cơm bento không?\nB: Vâng, xin vui lòng hâm giúp tôi.\nA: Quý khách có dùng túi nilon không ạ?\nB: Không, tôi không cần túi đâu.\nA: Tổng tiền thanh toán là 500 yên.\nB: Tôi sẽ thanh toán bằng PayPay.");
                        } else if (title.contains("Gọi món") || title.contains("quán ăn")) {
                            s.setMeaningContent("A: Xin lỗi, cho tôi gọi món với.\nB: Vâng, quý khách muốn dùng món gì ạ?\nA: Cho tôi một bát ramen và một đĩa gyoza.\nB: Tôi đã hiểu. Quý khách có muốn gọi đồ uống gì không?\nA: Cho tôi một ly nước lọc.");
                        } else if (title.contains("Hỏi đường") || title.contains("ga tàu")) {
                            s.setMeaningContent("A: Xin lỗi, ga Tokyo ở đâu vậy ạ?\nB: Bạn đi thẳng con đường này, rồi rẽ phải ở cột đèn giao thông nhé.\nA: Đi bộ mất khoảng bao lâu vậy ạ?\nB: Tầm khoảng 5 phút thôi bạn.\nA: Xin cảm ơn bạn rất nhiều. May quá.");
                        } else if (title.contains("English") || title.contains("Coffee") || "en".equalsIgnoreCase(s.getLanguage())) {
                            s.setMeaningContent("A: Xin chào! Hôm nay bạn thế nào?\nB: Chào bạn! Tôi khỏe, cảm ơn bạn. Còn bạn thì sao?\nA: Tôi cũng rất ổn. Chiều nay bạn có rảnh không?\nB: Có, tôi rảnh. Cùng đi uống cà phê nhé!\nA: Nghe tuyệt vời đấy!");
                        }
                        changed = true;
                    }
                    if (changed) {
                        scriptRepository.save(s);
                    }
                }
            }
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
