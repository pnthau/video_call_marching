  package com.example.videocall_marching_language.utils;                                                                                                                       
                                                                                                                                                                                 
    import java.text.Normalizer;                                                                                                                                                 
    import java.util.Locale;                                                                                                                                                     
    import java.util.regex.Pattern;                                                                                                                                              
                                                                                                                                                                                 
    public class TextNormalizationUtil {                                                                                                                                         
                                                                                                                                                                                 
        private static final Pattern EN_FILLER_WORDS = Pattern.compile(                                                                                                          
                "\\b(um|uh|er|ah|hmm|hm|eh)\\b", Pattern.CASE_INSENSITIVE                                                                                                        
        );                                                                                                                                                                       
                                                                                                                                                                                 
        private static final Pattern JA_FILLER_WORDS = Pattern.compile(                                                                                                          
                "(えーと|あのー|あの|ええと|ええ|うーん|そのー|その|なんか)"                                                                                                     
        );                                                                                                                                                                       
                                                                                                                                                                                 
        private static final Pattern VI_FILLER_WORDS = Pattern.compile(                                                                                                          
                "\\b(ừm|ờ|à|ừ|hở|ơ)\\b", Pattern.CASE_INSENSITIVE                                                                                                                
        );                                                                                                                                                                       
                                                                                                                                                                                 
        private static final Pattern REPEATED_WORDS = Pattern.compile(                                                                                                           
                "\\b(\\p{L}+)\\s+\\1\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS                                                                             
        );                                                                                                                                                                       
                                                                                                                                                                                 
        private TextNormalizationUtil() {}                                                                                                                                       
                                                                                                                                                                                 
        public static String normalizeOriginalSentence(String text, String languageCode) {                                                                                       
            if (text == null || text.isBlank()) return "";                                                                                                                       
            return formatWhitespaceByLanguage(cleanUnicodeAndPunctuation(text), languageCode);                                                                                   
        }                                                                                                                                                                        
                                                                                                                                                                                 
        public static String normalizeUserSpeech(String speechText, String languageCode) {                                                                                       
            if (speechText == null || speechText.isBlank()) return "";                                                                                                           
                                                                                                                                                                                 
            String text = Normalizer.normalize(speechText, Normalizer.Form.NFKC)                                                                                                 
                    .replaceAll("[\\u200B\\u00A0\\uFEFF]", " ")                                                                                                                  
                    .toLowerCase(Locale.ROOT)                                                                                                                                    
                    .replaceAll("['’`\"]", "");                                                                                                                                  
                                                                                                                                                                                 
            text = removeFillerWords(text, languageCode);                                                                                                                        
                                                                                                                                                                                 
            if (!"ja".equalsIgnoreCase(languageCode) && !"zh".equalsIgnoreCase(languageCode)) {                                                                                  
                text = REPEATED_WORDS.matcher(text).replaceAll("$1");                                                                                                            
            }                                                                                                                                                                    
                                                                                                                                                                                 
            text = text.replaceAll("[\\p{P}\\p{S}]", " ");                                                                                                                       
            return formatWhitespaceByLanguage(text, languageCode);                                                                                                               
        }                                                                                                                                                                        
                                                                                                                                                                                 
        /**                                                                                                                                                                      
         * Tính tỷ lệ tương đồng giữa 2 chuỗi (từ 0.0 đến 1.0)                                                                                                                   
         */                                                                                                                                                                      
        public static double calculateSimilarity(String s1, String s2) {                                                                                                         
            if (s1.equals(s2)) return 1.0;                                                                                                                                       
            if (s1.isEmpty() || s2.isEmpty()) return 0.0;                                                                                                                        
                                                                                                                                                                                 
            int distance = calculateLevenshteinDistance(s1, s2);                                                                                                                 
            int maxLength = Math.max(s1.length(), s2.length());                                                                                                                  
            return 1.0 - ((double) distance / maxLength);                                                                                                                        
        }                                                                                                                                                                        
                                                                                                                                                                                 
        /**                                                                                                                                                                      
         * Thuật toán Levenshtein Distance đo khoảng cách sai khác                                                                                                               
         */                                                                                                                                                                      
        private static int calculateLevenshteinDistance(String a, String b) {                                                                                                    
            int[] costs = new int[b.length() + 1];                                                                                                                               
            for (int j = 0; j < costs.length; j++) {                                                                                                                             
                costs[j] = j;                                                                                                                                                    
            }                                                                                                                                                                    
            for (int i = 1; i <= a.length(); i++) {                                                                                                                              
                costs[0] = i;                                                                                                                                                    
                int nw = i - 1;                                                                                                                                                  
                for (int j = 1; j <= b.length(); j++) {                                                                                                                          
                    int cj = Math.min(                                                                                                                                           
                            1 + Math.min(costs[j], costs[j - 1]),                                                                                                                
                            a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1                                                                                                     
                    );                                                                                                                                                           
                    nw = costs[j];                                                                                                                                               
                    costs[j] = cj;                                                                                                                                               
                }                                                                                                                                                                
            }                                                                                                                                                                    
            return costs[b.length()];                                                                                                                                            
        }                                                                                                                                                                        
                                                                                                                                                                                 
        private static String cleanUnicodeAndPunctuation(String text) {                                                                                                          
            return Normalizer.normalize(text, Normalizer.Form.NFKC)                                                                                                              
                    .toLowerCase(Locale.ROOT)                                                                                                                                    
                    .replaceAll("['’`\"]", "")                                                                                                                                   
                    .replaceAll("[\\p{P}\\p{S}]", " ");                                                                                                                          
        }                                                                                                                                                                        
                                                                                                                                                                                 
        private static String removeFillerWords(String text, String languageCode) {                                                                                              
            if (languageCode == null) return text;                                                                                                                               
            return switch (languageCode.toLowerCase(Locale.ROOT)) {                                                                                                              
                case "en" -> EN_FILLER_WORDS.matcher(text).replaceAll(" ");                                                                                                      
                case "ja" -> JA_FILLER_WORDS.matcher(text).replaceAll("");                                                                                                       
                case "vi" -> VI_FILLER_WORDS.matcher(text).replaceAll(" ");                                                                                                      
                default -> text;                                                                                                                                                 
            };                                                                                                                                                                   
        }                                                                                                                                                                        
                                                                                                                                                                                 
        private static String formatWhitespaceByLanguage(String text, String languageCode) {                                                                                     
            if ("ja".equalsIgnoreCase(languageCode) || "zh".equalsIgnoreCase(languageCode)) {                                                                                    
                return text.replaceAll("\\s+", "");                                                                                                                              
            }                                                                                                                                                                    
            return text.replaceAll("\\s+", " ").trim();                                                                                                                          
        }                                                                                                                                                                        
    }  