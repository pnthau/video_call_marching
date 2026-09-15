package com.example.videocall_marching_language.service.audiolesson;

import com.example.videocall_marching_language.dto.LanguageOption;
import com.example.videocall_marching_language.dto.audiolesson.*;
import com.example.videocall_marching_language.dto.script.TopicWithCountDTO;
import com.example.videocall_marching_language.entity.*;
import com.example.videocall_marching_language.enums.AudioLessonStatus;
import com.example.videocall_marching_language.enums.ExerciseType;
import com.example.videocall_marching_language.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioLessonService {

    private final AudioStorageService audioStorageService;
    private final WhisperTranscriptionService whisperService;
    private final AiLessonGeneratorService aiLessonGeneratorService;
    private final GrammarEvaluationService grammarEvaluationService;

    private final IAudioLessonRepository audioLessonRepository;
    private final ILessonSentenceRepository lessonSentenceRepository;
    private final ISentenceExerciseRepository sentenceExerciseRepository;
    private final IScriptRepository scriptRepository;
    private final ITagRepository tagRepository;
    private final IUserRepository userRepository;
    private final PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AudioLessonDTO createLessonFromAudio(
            MultipartFile audioFile,
            Long tagId,
            String customTitle,
            Long userId) {
        return createLessonFromAudio(audioFile, tagId, customTitle, userId, null);
    }

    public AudioLessonDTO createLessonFromAudio(
            MultipartFile audioFile,
            Long tagId,
            String customTitle,
            Long userId,
            String level) {
        return createLessonFromAudio(audioFile, tagId, customTitle, userId, level, null);
    }

    public AudioLessonDTO createLessonFromAudio(
            MultipartFile audioFile,
            Long tagId,
            String customTitle,
            Long userId,
            String level,
            String language) {

        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Chủ đề (Tag) với id: " + tagId));

        User user = (userId != null) ? userRepository.findById(userId).orElse(null) : null;

        // 1. Tải audio lên Cloudinary (ngoài transaction để tránh chiếm connection)
        AudioStorageService.AudioUploadResult uploadResult = audioStorageService.uploadAudio(audioFile);

        // 2. Bóc tách âm thanh thành câu có timestamp qua Groq Whisper (ngoài transaction)
        WhisperTranscriptionService.WhisperTranscriptionResult whisperResult =
                whisperService.transcribe(audioFile, userId);

        String lang = (language != null && !language.isBlank() && !"all".equalsIgnoreCase(language))
                ? language.trim().toLowerCase()
                : ((whisperResult.language() != null && !whisperResult.language().isBlank())
                        ? whisperResult.language().trim().toLowerCase()
                        : "ja");

        String lessonLevel = (level != null && !level.isBlank())
                ? level.trim().toUpperCase()
                : ("en".equalsIgnoreCase(lang) ? "A1" : "N5");

        // 3. AI sinh bài tập, phân tích ngữ pháp và dịch nghĩa (ngoài transaction)
        GeneratedLessonDTO generatedLesson = aiLessonGeneratorService.generateLessonContent(
                userId,
                whisperResult.segments(),
                lang,
                tag.getName()
        );

        String title = (customTitle != null && !customTitle.isBlank())
                ? customTitle
                : (generatedLesson.getLessonTitle() != null ? generatedLesson.getLessonTitle() : "Bài học: " + tag.getName());

        int duration = uploadResult.durationInSeconds() != null
                ? uploadResult.durationInSeconds()
                : (int) Math.round(whisperResult.duration());

        // 4 & 5. Mở transaction chỉ trong phạm vi ghi dữ liệu vào DB
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        Long savedLessonId = txTemplate.execute(status -> {
            AudioLesson audioLesson = AudioLesson.builder()
                    .tag(tag)
                    .user(user)
                    .title(title)
                    .audioUrl(uploadResult.secureUrl())
                    .durationInSeconds(duration)
                    .language(lang)
                    .level(lessonLevel)
                    .status(AudioLessonStatus.READY)
                    .build();

            audioLesson = audioLessonRepository.save(audioLesson);

            List<LessonSentence> sentenceEntities = new ArrayList<>();

            if (generatedLesson.getSentences() != null) {
                for (GeneratedSentenceDTO sDto : generatedLesson.getSentences()) {
                    LessonSentence sentence = LessonSentence.builder()
                            .audioLesson(audioLesson)
                            .sentenceIndex(sDto.getSentenceIndex())
                            .startTime(sDto.getStartTime())
                            .endTime(sDto.getEndTime())
                            .originalText(sDto.getOriginalText())
                            .phonetic(sDto.getPhonetic())
                            .vietnameseMeaning(sDto.getVietnameseMeaning() != null ? sDto.getVietnameseMeaning() : "")
                            .build();

                    // Ngữ pháp
                    SentenceGrammar grammar = SentenceGrammar.builder()
                            .lessonSentence(sentence)
                            .grammarPoint(sDto.getGrammarPoint() != null ? sDto.getGrammarPoint() : "Mẫu câu giao tiếp")
                            .explanation(sDto.getExplanation() != null ? sDto.getExplanation() : "")
                            .formula(sDto.getFormula())
                            .keyWordsJson(sDto.getKeyWordsJson() != null ? sDto.getKeyWordsJson() : "[]")
                            .sentenceChallengePrompt(sDto.getSentenceChallengePrompt() != null
                                    ? sDto.getSentenceChallengePrompt()
                                    : "Hãy đặt một câu tương tự để luyện tập.")
                            .build();
                    sentence.setGrammar(grammar);

                    // Bài tập trắc nghiệm
                    String optionsJson = "[]";
                    try {
                        if (sDto.getOptions() != null) {
                            optionsJson = objectMapper.writeValueAsString(sDto.getOptions());
                        }
                    } catch (Exception ignored) {}

                    ExerciseType exType = ExerciseType.LISTENING_FILL_BLANK;
                    try {
                        if (sDto.getExerciseType() != null) {
                            exType = ExerciseType.valueOf(sDto.getExerciseType());
                        }
                    } catch (Exception ignored) {}

                    SentenceExercise exercise = SentenceExercise.builder()
                            .lessonSentence(sentence)
                            .exerciseType(exType)
                            .question(sDto.getQuestion() != null ? sDto.getQuestion() : "Chọn phương án thích hợp:")
                            .optionsJson(optionsJson)
                            .correctAnswer(sDto.getCorrectAnswer() != null ? sDto.getCorrectAnswer() : "")
                            .explanation(sDto.getExerciseExplanation())
                            .build();

                    sentence.getExercises().add(exercise);
                    sentenceEntities.add(sentence);
                }
            }

            lessonSentenceRepository.saveAll(sentenceEntities);
            log.info("Lưu thành công AudioLesson id={}, số câu={}", audioLesson.getId(), sentenceEntities.size());
            return audioLesson.getId();
        });

        return getLessonById(savedLessonId);
    }

    @Transactional(readOnly = true)
    public AudioLessonDTO getLessonById(Long id) {
        AudioLesson lesson = audioLessonRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài học Audio với id: " + id));

        List<LessonSentence> sentences = lessonSentenceRepository.findByAudioLessonIdOrderBySentenceIndexAsc(id);

        List<LessonSentenceDTO> sentenceDTOs = sentences.stream().map(s -> {
            SentenceGrammar g = s.getGrammar();
            SentenceGrammarDTO grammarDTO = null;
            if (g != null) {
                grammarDTO = SentenceGrammarDTO.builder()
                        .id(g.getId())
                        .grammarPoint(g.getGrammarPoint())
                        .explanation(g.getExplanation())
                        .formula(g.getFormula())
                        .keyWordsJson(g.getKeyWordsJson())
                        .sentenceChallengePrompt(g.getSentenceChallengePrompt())
                        .build();
            }

            List<SentenceExerciseDTO> exerciseDTOs = s.getExercises().stream().map(e -> {
                List<String> options = new ArrayList<>();
                try {
                    options = objectMapper.readValue(e.getOptionsJson(), new TypeReference<List<String>>() {});
                } catch (Exception ignored) {}

                return SentenceExerciseDTO.builder()
                        .id(e.getId())
                        .exerciseType(e.getExerciseType().name())
                        .question(e.getQuestion())
                        .optionsJson(e.getOptionsJson())
                        .options(options)
                        .correctAnswer(e.getCorrectAnswer())
                        .explanation(e.getExplanation())
                        .build();
            }).toList();

            return LessonSentenceDTO.builder()
                    .id(s.getId())
                    .sentenceIndex(s.getSentenceIndex())
                    .startTime(s.getStartTime())
                    .endTime(s.getEndTime())
                    .originalText(s.getOriginalText())
                    .phonetic(s.getPhonetic())
                    .vietnameseMeaning(s.getVietnameseMeaning())
                    .grammar(grammarDTO)
                    .exercises(exerciseDTOs)
                    .build();
        }).toList();

        return AudioLessonDTO.builder()
                .id(lesson.getId())
                .tagId(lesson.getTag() != null ? lesson.getTag().getId() : null)
                .tagName(lesson.getTag() != null ? lesson.getTag().getName() : "")
                .userId(lesson.getUser() != null ? lesson.getUser().getId() : null)
                .title(lesson.getTitle())
                .audioUrl(lesson.getAudioUrl())
                .durationInSeconds(lesson.getDurationInSeconds())
                .language(lesson.getLanguage())
                .level(lesson.getLevel())
                .status(lesson.getStatus().name())
                .scriptId(lesson.getScript() != null ? lesson.getScript().getId() : null)
                .createdAt(lesson.getCreatedAt() != null ? lesson.getCreatedAt().toString() : null)
                .sentences(sentenceDTOs)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AudioLessonDTO> getAllLessons() {
        return audioLessonRepository.findAllWithTag().stream().map(l -> AudioLessonDTO.builder()
                .id(l.getId())
                .tagId(l.getTag() != null ? l.getTag().getId() : null)
                .tagName(l.getTag() != null ? l.getTag().getName() : "")
                .title(l.getTitle())
                .audioUrl(l.getAudioUrl())
                .durationInSeconds(l.getDurationInSeconds())
                .language(l.getLanguage())
                .level(l.getLevel())
                .status(l.getStatus().name())
                .scriptId(l.getScript() != null ? l.getScript().getId() : null)
                .createdAt(l.getCreatedAt() != null ? l.getCreatedAt().toString() : null)
                .build()
        ).toList();
    }

    @Transactional(readOnly = true)
    public List<AudioLessonDTO> findLessonsByCriteria(AudioLessonRequest request) {
        if (request == null) {
            return getAllLessons();
        }

        Long tagId = request.getTagId();

        String language = (request.getLanguage() != null && !request.getLanguage().isBlank() && !"all".equalsIgnoreCase(request.getLanguage()))
                ? request.getLanguage().trim().toLowerCase()
                : null;

        String level = (request.getLevel() != null && !request.getLevel().isBlank() && !"all".equalsIgnoreCase(request.getLevel()))
                ? request.getLevel().trim().toLowerCase()
                : null;

        List<AudioLesson> lessons = audioLessonRepository.findByCriteria(tagId, language, level);

        return lessons.stream().map(l -> AudioLessonDTO.builder()
                .id(l.getId())
                .tagId(l.getTag() != null ? l.getTag().getId() : null)
                .tagName(l.getTag() != null ? l.getTag().getName() : "")
                .userId(l.getUser() != null ? l.getUser().getId() : null)
                .title(l.getTitle())
                .audioUrl(l.getAudioUrl())
                .durationInSeconds(l.getDurationInSeconds())
                .language(l.getLanguage())
                .level(l.getLevel())
                .status(l.getStatus().name())
                .scriptId(l.getScript() != null ? l.getScript().getId() : null)
                .createdAt(l.getCreatedAt() != null ? l.getCreatedAt().toString() : null)
                .build()
        ).toList();
    }

    public List<TopicWithCountDTO> getTopicsWithAudioLessonCount() {
        return audioLessonRepository.findTopicsWithAudioLessonCount();
    }

    public String getTopicsWithCountAsJson() {
        try {
            List<TopicWithCountDTO> list = getTopicsWithAudioLessonCount();
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("Lỗi serialize danh sách topics audio sang JSON: {}", e.getMessage());
            return "[]";
        }
    }

    public List<String> getAvailableLanguages() {
        List<String> languages = audioLessonRepository.findDistinctLanguages();
        if (languages == null || languages.isEmpty()) {
            return List.of("ja", "en");
        }
        return languages;
    }

    public List<LanguageOption> getAvailableLanguageOptions() {
        return LanguageOption.fromCodes(getAvailableLanguages());
    }

    public GrammarChallengeResponseDTO evaluateGrammarChallenge(GrammarChallengeRequestDTO request, Long userId) {
        return grammarEvaluationService.evaluateSentence(userId, request);
    }

    public ExerciseCheckResponseDTO checkExercise(ExerciseCheckRequestDTO request) {
        if (request == null || request.getExerciseId() == null) {
            return ExerciseCheckResponseDTO.builder()
                    .isCorrect(false)
                    .explanation("Dữ liệu không hợp lệ")
                    .build();
        }

        SentenceExercise exercise = sentenceExerciseRepository.findById(request.getExerciseId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài tập id: " + request.getExerciseId()));

        String correct = exercise.getCorrectAnswer() != null ? exercise.getCorrectAnswer().trim() : "";
        String user = request.getUserAnswer() != null ? request.getUserAnswer().trim() : "";

        boolean isCorrect = correct.equalsIgnoreCase(user);

        return ExerciseCheckResponseDTO.builder()
                .isCorrect(isCorrect)
                .correctAnswer(correct)
                .explanation(exercise.getExplanation() != null ? exercise.getExplanation() : (isCorrect ? "Chính xác!" : "Chưa chính xác."))
                .build();
    }

    @Transactional
    public Long getOrCreateRoleplayScript(Long lessonId) {
        AudioLesson lesson = audioLessonRepository.findById(lessonId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài học Audio với id: " + lessonId));

        if (lesson.getScript() != null) {
            return lesson.getScript().getId();
        }

        List<LessonSentence> sentences = lessonSentenceRepository.findByAudioLessonIdOrderBySentenceIndexAsc(lessonId);
        if (sentences.isEmpty()) {
            throw new IllegalStateException("Bài học chưa có câu thoại để tạo kịch bản roleplay.");
        }

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder phoneticBuilder = new StringBuilder();
        StringBuilder meaningBuilder = new StringBuilder();

        for (int i = 0; i < sentences.size(); i++) {
            LessonSentence s = sentences.get(i);
            String role = (i % 2 == 0) ? "A" : "B";

            String orig = (s.getOriginalText() != null && !s.getOriginalText().isBlank())
                    ? s.getOriginalText().trim() : "...";
            String phon = (s.getPhonetic() != null && !s.getPhonetic().isBlank())
                    ? s.getPhonetic().trim() : orig;
            String mean = (s.getVietnameseMeaning() != null && !s.getVietnameseMeaning().isBlank())
                    ? s.getVietnameseMeaning().trim() : orig;

            contentBuilder.append(role).append(": ").append(orig).append("\n");
            phoneticBuilder.append(role).append(": ").append(phon).append("\n");
            meaningBuilder.append(role).append(": ").append(mean).append("\n");
        }

        String scriptTitle = "[Roleplay] " + (lesson.getTitle() != null ? lesson.getTitle() : "Luyện đối đáp");
        String lang = lesson.getLanguage() != null ? lesson.getLanguage() : "ja";
        String scriptLevel = (lesson.getLevel() != null && !lesson.getLevel().isBlank())
                ? lesson.getLevel()
                : ("en".equalsIgnoreCase(lang) ? "A1" : "N5");
        Script script = Script.builder()
                .title(scriptTitle)
                .tag(lesson.getTag())
                .language(lang)
                .level(scriptLevel)
                .targetDuration(lesson.getDurationInSeconds() != null ? lesson.getDurationInSeconds() : 60)
                .content(contentBuilder.toString().trim())
                .phoneticContent(phoneticBuilder.toString().trim())
                .meaningContent(meaningBuilder.toString().trim())
                .build();

        script = scriptRepository.save(script);
        lesson.setScript(script);
        audioLessonRepository.save(lesson);

        log.info("Đã tạo kịch bản roleplay scriptId={} cho AudioLesson id={}", script.getId(), lesson.getId());
        return script.getId();
    }
}
