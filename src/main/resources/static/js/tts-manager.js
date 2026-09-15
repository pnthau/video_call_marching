// --- BỘ QUẢN LÝ & CHỌN GIỌNG ĐỌC WEB SPEECH API ĐA TẦNG ---
const WebSpeechVoiceResolver = {
    voiceCache: new Map(),
    preferredNames: {
        'ja': ['nanami'],
        'vi': ['hoaimy', 'hoai my'],
        'en': ['jenny'],
        'ko': ['sunhi', 'sun-hi'],
        'zh': ['xiaoxiao', 'xiao xiao'],
        'fr': ['denise'],
        'es': ['elvira']
    },
    qualityKeywords: ['natural', 'online', 'google', 'neural', 'enhanced', 'premium'],

    normalizeLang: function(lang) {
        if (!lang) return 'ja';
        const clean = lang.trim().toLowerCase().replace('_', '-');
        if (clean.startsWith('ja') || clean.includes('japan')) return 'ja';
        if (clean.startsWith('vi') || clean.includes('viet')) return 'vi';
        if (clean.startsWith('en') || clean.includes('eng')) return 'en';
        if (clean.startsWith('ko') || clean.includes('korea')) return 'ko';
        if (clean.startsWith('zh') || clean.includes('chin')) return 'zh';
        if (clean.startsWith('fr') || clean.includes('french')) return 'fr';
        if (clean.startsWith('es') || clean.includes('span')) return 'es';
        return clean.split('-')[0];
    },

    getStandardBcp47: function(lang) {
        const prefix = this.normalizeLang(lang);
        const map = {
            'ja': 'ja-JP',
            'vi': 'vi-VN',
            'en': 'en-US',
            'ko': 'ko-KR',
            'zh': 'zh-CN',
            'fr': 'fr-FR',
            'es': 'es-ES'
        };
        return map[prefix] || (lang && lang.includes('-') ? lang : 'ja-JP');
    },

    loadVoicesAsync: function() {
        return new Promise((resolve) => {
            if (!('speechSynthesis' in window)) {
                resolve([]);
                return;
            }
            let voices = window.speechSynthesis.getVoices();
            if (voices && voices.length > 0) {
                resolve(voices);
                return;
            }
            let settled = false;
            const onVoices = () => {
                if (!settled) {
                    settled = true;
                    resolve(window.speechSynthesis.getVoices() || []);
                }
            };
            window.speechSynthesis.addEventListener('voiceschanged', onVoices, { once: true });
            setTimeout(() => {
                if (!settled) {
                    settled = true;
                    resolve(window.speechSynthesis.getVoices() || []);
                }
            }, 800);
        });
    },

    resolveBestVoice: async function(langCode) {
        const prefix = this.normalizeLang(langCode);
        if (this.voiceCache.has(prefix)) {
            return this.voiceCache.get(prefix);
        }

        const voices = await this.loadVoicesAsync();
        if (!voices || voices.length === 0) return null;

        // Lọc danh sách giọng thuộc ngôn ngữ đích
        const targetVoices = voices.filter(v => {
            if (!v.lang) return false;
            const vLang = v.lang.toLowerCase().replace('_', '-');
            return vLang === prefix || vLang.startsWith(prefix + '-');
        });

        let selected = null;

        if (targetVoices.length > 0) {
            // TẦNG 1: Khớp nhân vật Edge-TTS tương đồng (Nanami, HoaiMy, Jenny...)
            const targetNames = this.preferredNames[prefix] || [];
            for (const name of targetNames) {
                selected = targetVoices.find(v => (v.name || '').toLowerCase().includes(name));
                if (selected) {
                    console.log(`🎯 [WebSpeech Tầng 1 - Edge Match] Chọn giọng: "${selected.name}" (${selected.lang})`);
                    break;
                }
            }

            // TẦNG 2: Giọng chất lượng cao (Natural / Online / Google / Neural...)
            if (!selected) {
                selected = targetVoices.find(v => {
                    const name = (v.name || '').toLowerCase();
                    return this.qualityKeywords.some(kw => name.includes(kw));
                });
                if (selected) {
                    console.log(`✨ [WebSpeech Tầng 2 - Quality Match] Chọn giọng: "${selected.name}" (${selected.lang})`);
                }
            }

            // TẦNG 3: Giọng hợp lệ đầu tiên đúng mã ngôn ngữ
            if (!selected) {
                selected = targetVoices[0];
                console.log(`📌 [WebSpeech Tầng 3 - Locale First Match] Chọn giọng: "${selected.name}" (${selected.lang})`);
            }
        } else {
            // TẦNG 4: CỨU HỘ - Lấy đại giọng sẵn có trên trình duyệt (default hoặc phần tử đầu tiên)
            selected = voices.find(v => v.default) || voices[0] || null;
            if (selected) {
                console.warn(`⚠️ [WebSpeech Tầng 4 - Emergency Fallback] Thiết bị không có voice cho [${prefix}]. Lấy đại giọng sẵn có: "${selected.name}" (${selected.lang})`);
            }
        }

        if (selected) {
            this.voiceCache.set(prefix, selected);
        }
        return selected;
    }
};

// Kích hoạt Pre-warming ngay khi tải script
if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
    window.speechSynthesis.getVoices();
    window.speechSynthesis.addEventListener('voiceschanged', () => {
        window.speechSynthesis.getVoices();
    }, { once: true });
}

const TtsManager = {
    currentAudioObj: null,
    activeSequenceId: 0,
    serverUnavailable: false,
    nextServerRetryTime: 0,

    stopAudio: function() {
        this.activeSequenceId++;
        if (this.currentAudioObj) {
            try {
                this.currentAudioObj.pause();
                this.currentAudioObj.currentTime = 0;
            } catch (e) {}
            this.currentAudioObj = null;
        }
        if ('speechSynthesis' in window) {
            try {
                window.speechSynthesis.cancel();
            } catch (e) {}
        }
    },

    // Cho phép reset trạng thái server thủ công khi cần
    resetServerStatus: function() {
        this.serverUnavailable = false;
        this.nextServerRetryTime = 0;
    },

    playAudioElement: function(base64) {
        return new Promise((resolve) => {
            if (this.currentAudioObj) {
                try {
                    this.currentAudioObj.pause();
                    this.currentAudioObj.currentTime = 0;
                } catch (e) {}
                this.currentAudioObj = null;
            }
            const audio = new Audio("data:audio/mp3;base64," + base64);
            this.currentAudioObj = audio;
            
            audio.onended = () => {
                if (this.currentAudioObj === audio) {
                    this.currentAudioObj = null;
                }
                resolve(true);
            };
            audio.onerror = () => {
                if (this.currentAudioObj === audio) {
                    this.currentAudioObj = null;
                }
                resolve(false);
            };
            audio.play().catch(e => {
                console.error("Lỗi phát audio base64:", e);
                if (this.currentAudioObj === audio) {
                    this.currentAudioObj = null;
                }
                resolve(false);
            });
        });
    },

    playAudioBase64: function(base64) {
        this.stopAudio();
        return this.playAudioElement(base64);
    },

    playWebSpeechUtterance: async function(text, langCode) {
        return new Promise(async (resolve) => {
            if (!('speechSynthesis' in window)) {
                console.warn("Trình duyệt không hỗ trợ Web Speech API.");
                resolve(false);
                return;
            }
            if (this.currentAudioObj) {
                try {
                    this.currentAudioObj.pause();
                } catch (e) {}
                this.currentAudioObj = null;
            }
            try {
                window.speechSynthesis.cancel();
            } catch (e) {}

            let voice = await WebSpeechVoiceResolver.resolveBestVoice(langCode);
            const bcp47 = WebSpeechVoiceResolver.getStandardBcp47(langCode);

            // Double Safety Net: Nếu voice vẫn null, quét ngay voices hiện thời lấy đại 1 giọng
            if (!voice && ('speechSynthesis' in window)) {
                const currentVoices = window.speechSynthesis.getVoices() || [];
                voice = currentVoices.find(v => v.default) || currentVoices[0] || null;
            }

            const utterance = new SpeechSynthesisUtterance(text);
            utterance.rate = 0.90;

            // GÁN TRỰC TIẾP GIỌNG ĐỌC - GIẢI PHÁP TRIỆT ĐỂ CHO LỖI CÂM TIẾNG TRÊN WINDOWS
            if (voice) {
                utterance.voice = voice;
                // Khi dùng fallback voice, gán theo lang của voice đó để tránh engine SAPI từ chối
                utterance.lang = voice.lang || bcp47;
            } else {
                utterance.lang = bcp47;
                console.warn(`[TTS] Không có voice nào trên hệ thống. Thử phát với bcp47 [${bcp47}].`);
            }

            utterance.onend = () => resolve(true);
            utterance.onerror = (err) => {
                console.warn("SpeechSynthesis error:", err);
                resolve(false);
            };

            // Tránh race-condition trên Chrome giữa cancel() và speak()
            setTimeout(() => {
                window.speechSynthesis.speak(utterance);
            }, 40);
        });
    },

    playWebSpeechApi: function(text, langCode) {
        this.stopAudio();
        return this.playWebSpeechUtterance(text, langCode);
    },

    clientAudioCache: new Map(),

    cleanTtsText: function(text) {
        if (!text) return '';
        return text
            .replace(/&quot;/g, '"')
            .replace(/&#039;/g, "'")
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/^["“”«»'\s]+|["“”«»'\s]+$/g, '')
            .trim();
    },

    fetchAudioBase64: async function(text, langCode) {
        const cleanText = this.cleanTtsText(text);
        if (!cleanText) return null;

        const cacheKey = (langCode || 'ja') + ':' + cleanText;
        if (this.clientAudioCache.has(cacheKey)) {
            console.log(`⚡ [BROWSER CACHE HIT] "${cleanText}" (0ms)`);
            return this.clientAudioCache.get(cacheKey);
        }

        // Circuit Breaker: Nếu Edge-TTS server đang offline và còn trong thời gian cooldown 30s
        const now = Date.now();
        if (this.serverUnavailable && now < this.nextServerRetryTime) {
            return null; // Fallback ngay sang Web Speech API (0ms), không gọi network
        }

        try {
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
            
            const headers = { 'Content-Type': 'application/json' };
            if (csrfHeader && csrfToken) {
                headers[csrfHeader] = csrfToken;
            }

            const res = await fetch('/api/practice/tts', {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({ text: cleanText, language: langCode })
            });

            if (res.ok) {
                const data = await res.json();
                if (data.status === 'success' && data.audioBase64) {
                    // Server hoạt động trở lại -> tự động hồi phục (Self-healing)
                    this.serverUnavailable = false;
                    this.nextServerRetryTime = 0;
                    if (this.clientAudioCache.size > 200) {
                        this.clientAudioCache.clear();
                    }
                    this.clientAudioCache.set(cacheKey, data.audioBase64);
                    return data.audioBase64;
                } else if (data.status === 'fallback') {
                    console.info("ℹ️ [TTS] Edge-TTS server offline, tự động chuyển sang Web Speech API.");
                    this.serverUnavailable = true;
                    this.nextServerRetryTime = now + 30000;
                    return null;
                }
            } else {
                this.serverUnavailable = true;
                this.nextServerRetryTime = now + 30000;
            }
        } catch (e) {
            console.info("ℹ️ [TTS] Không thể kết nối TTS backend, chuyển sang Web Speech API.");
            this.serverUnavailable = true;
            this.nextServerRetryTime = Date.now() + 30000;
        }
        return null;
    },

    playTts: async function(text, langCode) {
        const cleanText = this.cleanTtsText(text);
        if (!cleanText) return;

        const audioBase64 = await this.fetchAudioBase64(cleanText, langCode);
        if (audioBase64) {
            const success = await this.playAudioBase64(audioBase64);
            if (success) return;
        }

        // Fallback sang Web Speech API nếu không lấy được Base64 từ server hoặc server offline
        await this.playWebSpeechApi(cleanText, langCode);
    },

    playTtsSegmentsSequential: async function(segments) {
        if (!Array.isArray(segments) || segments.length === 0) return;
        
        this.stopAudio();
        const mySeqId = this.activeSequenceId;

        console.group(`🔊 [TTS Siêu Tốc - Giải Pháp 1] Xử lý mảng ${segments.length} đoạn:`);
        const startTime = Date.now();

        // 1. DÙNG NGAY AUDIO ĐÍNH KÈM TỪ BACKEND (0ms) HOẶC TẢI SONG SONG
        const audioPromises = segments.map((seg, idx) => {
            if (!seg || !seg.text || !seg.text.trim()) return Promise.resolve(null);
            const clean = this.cleanTtsText(seg.text);
            const cacheKey = (seg.lang || 'ja') + ':' + clean;

            // Nếu Backend đã tạo sẵn audioBase64 (Giải pháp 1) -> Dùng ngay, 0ms, không tốn request
            if (seg.audioBase64 && seg.audioBase64.trim().length > 0) {
                console.log(`  ⚡ [Đã có sẵn từ Backend] Segment #${idx + 1} [${seg.lang}] -> 0ms!`);
                this.clientAudioCache.set(cacheKey, seg.audioBase64);
                return Promise.resolve(seg.audioBase64);
            }

            // Nếu đã lưu trong Cache RAM trình duyệt
            if (this.clientAudioCache.has(cacheKey)) {
                console.log(`  ⚡ [Browser Cache Hit] Segment #${idx + 1} [${seg.lang}] -> 0ms!`);
                return Promise.resolve(this.clientAudioCache.get(cacheKey));
            }

            // Fallback: Gọi tải song song nếu chưa có
            return this.fetchAudioBase64(clean, seg.lang)
                .then(b64 => {
                    const elapsed = Date.now() - startTime;
                    console.log(`  ⚡ [Đã tải xong Segment #${idx + 1} sau ${elapsed}ms] [${seg.lang}] -> "${clean}"`);
                    if (b64) this.clientAudioCache.set(cacheKey, b64);
                    return b64;
                })
                .catch(err => {
                    console.warn(`Lỗi tải audio segment #${idx + 1}:`, err);
                    return null;
                });
        });

        // 2. PHÁT TUẦN TỰ MƯỢT MÀ, ĐOẠN SAU ĐÃ ĐƯỢC TẢI SẴN TRONG BỘ NHỚ
        for (let i = 0; i < segments.length; i++) {
            if (this.activeSequenceId !== mySeqId) {
                console.log("Audio sequence stopped.");
                break;
            }

            const seg = segments[i];
            if (!seg || !seg.text || !seg.text.trim()) continue;
            const clean = this.cleanTtsText(seg.text);
            if (!clean) continue;

            console.log(`  ▶️ [Đang đọc Segment #${i + 1}/${segments.length}] [${seg.lang}] -> "${clean}"`);
            
            // Lấy kết quả từ Promise đã bắt đầu tải từ trước (hầu như đã sẵn sàng 100%)
            const audioBase64 = await audioPromises[i];
            
            if (this.activeSequenceId !== mySeqId) break;

            if (audioBase64) {
                await this.playAudioElement(audioBase64);
            } else {
                await this.playWebSpeechUtterance(clean, seg.lang);
            }
            
            if (this.activeSequenceId !== mySeqId) break;

            // Khoảng nghỉ tự nhiên giữa 2 câu rút ngắn xuống 120ms
            if (i < segments.length - 1) {
                await new Promise(r => setTimeout(r, 120));
            }
        }
        console.groupEnd();
    }
};
