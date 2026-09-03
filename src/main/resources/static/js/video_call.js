window.onload = function() {
    const urlParams = new URLSearchParams(window.location.search);
    const uidFromUrl = urlParams.get('uid');
    if (uidFromUrl) {
        document.getElementById("uid").value = uidFromUrl;
    } else {
        document.getElementById("uid").value = "1";
    }
}

// --- CẤU HÌNH AGORA ---
const client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
let localAudioTrack = null;
let localVideoTrack = null;
let isAudioMuted = false;
let isVideoMuted = false;

// --- CẤU HÌNH WEBSOCKET (STOMP) ---
let stompClient = null;
let currentUserId = null;
let currentTagKey = null;

// --- CHẾ ĐỘ CUỘC GỌI ---
let isAiCall = false;
let aiChatHistory = [];
let recognition = null;
const synth = window.speechSynthesis;

// --- GIAO DIỆN (UI PANELS) ---
const setupPanel = document.getElementById("setup-panel");
const waitingPanel = document.getElementById("waiting-panel");
const callPanel = document.getElementById("call-panel");
const videoContainer = document.getElementById("video-container");
const roomInfo = document.getElementById("room-info");
const remotePlayer = document.getElementById("remote-player");
const aiPlayerScreen = document.getElementById("ai-player-screen");
const remoteLabel = document.getElementById("remote-label");
const avatarWrapper = document.getElementById("avatar-wrapper");
const aiStatusText = document.getElementById("ai-status-text");
const subtitlesOverlay = document.getElementById("subtitles-overlay");
const feedbackCard = document.getElementById("feedback-card");

// --- GẮN SỰ KIỆN NÚT BẤM ---
document.getElementById("call-ai-btn").addEventListener("click", startAiCall);
document.getElementById("find-partner-btn").addEventListener("click", startSearch);
document.getElementById("cancel-search-btn").addEventListener("click", cancelSearch);
document.getElementById("end-call-btn").addEventListener("click", endCall);
document.getElementById("mic-btn").addEventListener("click", toggleMic);
document.getElementById("cam-btn").addEventListener("click", toggleCam);

// ==========================================
// 1. CHẾ ĐỘ GỌI 1-1 VỚI AI SENSEI (0 ĐỒNG)
// ==========================================

async function startAiCall() {
    isAiCall = true;
    currentUserId = document.getElementById("uid").value || "1";

    // 1. Chuyển UI sang màn hình Call
    setupPanel.style.display = "none";
    callPanel.style.display = "flex";
    videoContainer.style.display = "grid";
    remotePlayer.style.display = "none";
    aiPlayerScreen.style.display = "flex";
    remoteLabel.innerText = "🤖 AI Sensei (Tanaka)";
    roomInfo.innerText = "Phòng học 1-1 với AI Sensei (Chế độ: " + document.getElementById("ai-mode").value + ")";

    // 2. Mở Camera & Mic của User (Local Agora Track)
    try {
        localAudioTrack = await AgoraRTC.createMicrophoneAudioTrack();
        localVideoTrack = await AgoraRTC.createCameraVideoTrack();
        localVideoTrack.play("local-player");
    } catch (e) {
        console.warn("Không thể bật camera/mic qua Agora:", e);
    }

    // 3. Khởi tạo Web Speech Recognition tự động lắng nghe
    initSpeechRecognition();

    // 4. AI Sensei chào mở đầu
    const scenario = document.getElementById("ai-scenario").value;
    const greeting = "こんにちは！田中先生です。練習を始めましょう！";
    speakAiResponse({
        replyText: greeting,
        reading: "Konnichiwa! Tanaka sensei desu. Renshuu o hajimemashou!",
        translation: "Xin chào! Thầy Tanaka đây. Chúng ta hãy bắt đầu luyện tập nhé!"
    });
}

function initSpeechRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
        alert("Trình duyệt không hỗ trợ Web Speech API. Vui lòng dùng Chrome hoặc Edge.");
        return;
    }

    recognition = new SpeechRecognition();
    recognition.lang = 'ja-JP';
    recognition.continuous = false;
    recognition.interimResults = false;

    recognition.onresult = async function(event) {
        const userSpokenText = event.results[0][0].transcript;
        console.log("User đã nói:", userSpokenText);
        
        aiStatusText.innerText = "💭 AI đang suy nghĩ câu trả lời...";
        avatarWrapper.classList.remove("speaking");

        await sendToAiBackend(userSpokenText);
    };

    recognition.onend = function() {
        // Tự động lắng nghe lại nếu vẫn đang trong cuộc gọi và AI không đang nói
        if (isAiCall && !synth.speaking) {
            try { recognition.start(); } catch (e) {}
        }
    };

    recognition.onerror = function(event) {
        console.warn("Speech recognition error:", event.error);
    };

    try { recognition.start(); } catch (e) {}
}

async function sendToAiBackend(userMessage) {
    const mode = document.getElementById("ai-mode").value;
    const scenario = document.getElementById("ai-scenario").value;

    const requestDTO = {
        userMessage: userMessage,
        mode: mode,
        language: "ja",
        scenario: scenario,
        targetLevel: "N5",
        history: aiChatHistory.slice(-4)
    };

    try {
        const response = await fetch(`/api/ai-tutor/chat?userId=${currentUserId}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestDTO)
        });

        if (!response.ok) throw new Error("Lỗi API Backend");
        const data = await response.json();

        aiChatHistory.push({ role: 'user', text: userMessage });
        aiChatHistory.push({ role: 'model', text: data.replyText });

        // AI nói và hiển thị phụ đề
        speakAiResponse(data);

        // Hiển thị feedback sửa lỗi ngữ pháp
        if (data.feedback) {
            feedbackCard.style.display = "block";
            document.getElementById("feedback-content").innerText = data.feedback;
            document.getElementById("suggested-reply").innerText = data.suggestedReply ? "👉 Gợi ý nói tiếp: " + data.suggestedReply : "";
        }

    } catch (err) {
        console.error(err);
        aiStatusText.innerText = "⚠️ Lỗi kết nối AI.";
    }
}

function speakAiResponse(data) {
    synth.cancel();

    // Hiển thị phụ đề
    subtitlesOverlay.style.display = "block";
    document.getElementById("sub-jp").innerText = data.replyText;
    document.getElementById("sub-reading").innerText = data.reading ? "📝 " + data.reading : "";
    document.getElementById("sub-vi").innerText = data.translation ? "🇻🇳 " + data.translation : "";

    const utterance = new SpeechSynthesisUtterance(data.replyText);
    utterance.lang = 'ja-JP';
    utterance.rate = 0.95; // Tốc độ tự nhiên

    utterance.onstart = function() {
        aiStatusText.innerText = "🗣️ Tanaka Sensei đang nói...";
        avatarWrapper.classList.add("speaking");
        if (recognition) { try { recognition.stop(); } catch(e){} }
    };

    utterance.onend = function() {
        avatarWrapper.classList.remove("speaking");
        aiStatusText.innerText = "🎧 Đang lắng nghe bạn nói...";
        if (isAiCall && recognition) {
            try { recognition.start(); } catch(e){}
        }
    };

    synth.speak(utterance);
}

// ==========================================
// 2. LOGIC TÌM KIẾM ĐỐI TÁC NGƯỜI THẬT (P2P)
// ==========================================

function startSearch() {
    isAiCall = false;
    currentUserId = document.getElementById("uid").value;
    currentTagKey = document.getElementById("tag-key").value;

    if (!currentUserId || !currentTagKey) {
        alert("Vui lòng nhập ID và Tag!");
        return;
    }

    if (!stompClient || !stompClient.connected) {
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);

        stompClient.connect({}, function (frame) {
            stompClient.subscribe('/topic/match/' + currentUserId, function (message) {
                handleMatchMessage(JSON.parse(message.body));
            });
            sendJoinRequest();
        }, function(error) {
            alert("Lỗi kết nối Server! Vui lòng kiểm tra lại kết nối mạng.");
        });
    } else {
        sendJoinRequest();
    }
}

function sendJoinRequest() {
    setupPanel.style.display = "none";
    waitingPanel.style.display = "block";
    
    stompClient.send("/app/join", {}, JSON.stringify({ 
        'userId': parseInt(currentUserId), 
        'tagKey': currentTagKey 
    }));
}

function cancelSearch() {
    if (stompClient && stompClient.connected) {
        stompClient.send("/app/cancel-search", {}, JSON.stringify({ 
            'userId': parseInt(currentUserId), 
            'tagKey': currentTagKey 
        }));
    }
    waitingPanel.style.display = "none";
    setupPanel.style.display = "block";
}

function endCall() {
    if (isAiCall) {
        // Dừng AI
        isAiCall = false;
        synth.cancel();
        if (recognition) { try { recognition.stop(); } catch(e){} }
        cleanupMedia();
        resetToSetupUI();
    } else {
        if (stompClient && stompClient.connected) {
            stompClient.send("/app/cancel-search", {}, JSON.stringify({ 
                'userId': parseInt(currentUserId), 
                'tagKey': currentTagKey 
            }));
        }
        client.leave();
        cleanupMedia();
        resetToSetupUI();
    }
}

function cleanupMedia() {
    if (localAudioTrack) {
        localAudioTrack.stop();
        localAudioTrack.close();
        localAudioTrack = null;
    }
    if (localVideoTrack) {
        localVideoTrack.stop();
        localVideoTrack.close();
        localVideoTrack = null;
    }
}

function resetToSetupUI() {
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    feedbackCard.style.display = "none";
    setupPanel.style.display = "block";
    aiPlayerScreen.style.display = "none";
    remotePlayer.style.display = "block";
    remoteLabel.innerText = "Đối tác (Remote)";
}

// ==========================================
// 3. XỬ LÝ AGORA KHI MATCH NGƯỜI THẬT
// ==========================================

function handleMatchMessage(data) {
    if (data.status === "MATCHED") {
        waitingPanel.style.display = "none";
        callPanel.style.display = "flex";
        videoContainer.style.display = "grid";
        remotePlayer.style.display = "block";
        aiPlayerScreen.style.display = "none";
        remoteLabel.innerText = "Đối tác (ID: " + data.peerId + ")";
        roomInfo.innerText = "Đã kết nối với Bạn học (ID: " + data.peerId + ")";

        joinAgoraRoom(data.channelName);
    }
}

async function joinAgoraRoom(channelName) {
    try {
        const response = await fetch(`/api/agora/token?channelName=${channelName}&uid=${currentUserId}`);
        const token = await response.text();

        await client.join(AGORA_APP_ID, channelName, token, parseInt(currentUserId));

        localAudioTrack = await AgoraRTC.createMicrophoneAudioTrack();
        localVideoTrack = await AgoraRTC.createCameraVideoTrack();

        localVideoTrack.play("local-player");
        await client.publish([localAudioTrack, localVideoTrack]);

        client.on("user-published", async (user, mediaType) => {
            await client.subscribe(user, mediaType);
            if (mediaType === "video") {
                user.videoTrack.play("remote-player");
            }
            if (mediaType === "audio") {
                user.audioTrack.play();
            }
        });

    } catch (error) {
        console.error("Lỗi Agora:", error);
    }
}

function toggleMic() {
    if (localAudioTrack) {
        isAudioMuted = !isAudioMuted;
        localAudioTrack.setMuted(isAudioMuted);
        document.getElementById("mic-btn").innerText = isAudioMuted ? "🔇 Bật Mic" : "🎤 Tắt Mic";
    }
}

function toggleCam() {
    if (localVideoTrack) {
        isVideoMuted = !isVideoMuted;
        localVideoTrack.setMuted(isVideoMuted);
        document.getElementById("cam-btn").innerText = isVideoMuted ? "📷 Bật Camera" : "📷 Tắt Camera";
    }
}
