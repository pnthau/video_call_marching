package com.example.videocall_marching_language.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TextNormalizationUtilTest {

    @Test
    void testCurrentBug_MissingOneWord_GivesHighSimilarity() {
        // Original: "I go to school" (14 chars)
        // User says: "go to school" (12 chars)
        // Currently it uses char-based Levenshtein: distance=2, max=14 -> similarity = 1 - 2/14 = 0.857 (PASS!)
        // Missing the subject "I" is a major error, but it gives >= 85%.
        
        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence("I go to school", "en");
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech("go to school", "en");
        
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser, "en");
        
        // After fix, word-based distance: original has 4 words, user has 3 words.
        // Distance = 1 word. Max words = 4. Similarity = 1 - 1/4 = 0.75.
        // So similarity should be 0.75, not 0.857.
        assertEquals(0.75, similarity, 0.01);
    }
    
    @Test
    void testPerfectMatch() {
        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence("Hello world", "en");
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech("Hello world", "en");
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser, "en");
        assertEquals(1.0, similarity, 0.01);
    }
    
    @Test
    void testJapaneseCharBased() {
        // "こんにちは" (5 chars) vs "こんちは" (4 chars)
        // distance = 1, max = 5 -> sim = 0.8
        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence("こんにちは", "ja");
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech("こんちは", "ja");
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser, "ja");
        assertEquals(0.8, similarity, 0.01);
    }
    
    @Test
    void testVietnameseMissingWord() {
        // "Tôi đi học" (3 words) vs "đi học" (2 words)
        // distance = 1 word, max = 3 words -> sim = 0.66
        String cleanOriginal = TextNormalizationUtil.normalizeOriginalSentence("Tôi đi học", "vi");
        String cleanUser = TextNormalizationUtil.normalizeUserSpeech("đi học", "vi");
        double similarity = TextNormalizationUtil.calculateSimilarity(cleanOriginal, cleanUser, "vi");
        assertEquals(0.666, similarity, 0.01);
    }
}
