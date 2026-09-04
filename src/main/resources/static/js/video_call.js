function getCsrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
    const header = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
    const headers = {};
    if (token && header) {
        headers[header] = token;
    }
    return headers;
}

// --- CẤU HÌNH AGORA ---
const client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
let localAudioTrack = null;
let localVideoTrack = null;
let isAudioMuted = false;
let isVideoMuted = false;

// --- CẤU HÌNH WEBSOCKET (STOMP) ---
let stompClient = null;
let currentSessionId = null;
let currentChannelName = null;
let currentPeerId = null;
let currentTagKey = null;
let recoveryInProgress = false;
let recoveryJoinStarted = false;

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

const topicTagSelect = document.getElementById("tag-select-topic");
const levelTagSelect = document.getElementById("tag-select-level");
const activityTagSelect = document.getElementById("tag-select-activity");

// --- GẮN SỰ KIỆN NÚT BẤM ---
document.getElementById("call-ai-btn")?.addEventListener("click", startAiCall);
document.getElementById("find-partner-btn")?.addEventListener("click", startSearch);
document.getElementById("cancel-search-btn")?.addEventListener("click", cancelSearch);
document.getElementById("end-call-btn")?.addEventListener("click", endCall);
document.getElementById("mic-btn")?.addEventListener("click", toggleMic);
document.getElementById("cam-btn")?.addEventListener("click", toggleCam);
window.addEventListener("load", recoverActiveSession);

function connectWebSocket(onConnected) {
    if (stompClient && stompClient.connected) {
        onConnected();
        return;
    }
    const socket = new SockJS('/ws');
    stompClient = Stomp.over(socket);
    stompClient.connect({}, function () {
        stompClient.subscribe('/topic/match/' + CURRENT_USER_ID, function (message) {
            handleMatchMessage(JSON.parse(message.body));
        });
        onConnected();
    }, function () {
        showSetupPanel();
        alert("Unable to connect to the server. Please try again.");
    });
}

async function recoverActiveSession() {
    recoveryInProgress = true;
    recoveryJoinStarted = false;
    setupPanel.style.display = "none";
    waitingPanel.style.display = "block";
    waitingPanel.querySelector("p").innerText = "RECOVERING: restoring the active call...";
    try {
        const response = await fetch('/api/sessions/active', { credentials: 'include' });
        if (response.status === 204) {
            showSetupPanel();
            return;
        }
        if (!response.ok) {
            throw new Error("Unable to resolve the active session");
        }
        const session = await response.json();
        currentSessionId = session.id;
        currentChannelName = session.channelName;
        currentPeerId = session.currentUserId === session.user1Id ? session.user2Id : session.user1Id;
        connectWebSocket(function () {
            waitingPanel.querySelector("p").innerText = "RECONNECTING: joining the previous call...";
            stompClient.send('/app/recover-session', {}, JSON.stringify({}));
        });
    } catch (error) {
        recoveryInProgress = false;
        showSetupPanel();
        alert(error.message);
    }
}

function showSetupPanel() {
    recoveryInProgress = false;
    recoveryJoinStarted = false;
    waitingPanel.style.display = "none";
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    if (feedbackCard) feedbackCard.style.display = "none";
    if (aiPlayerScreen) aiPlayerScreen.style.display = "none";
    if (remotePlayer) remotePlayer.style.display = "block";
    if (remoteLabel) remoteLabel.innerText = "Đối tác (Remote)";
    setupPanel.style.display = "block";

    currentSessionId = null;
    currentChannelName = null;
    currentPeerId = null;
    currentTagKey = null;
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
    const localContainer = document.getElementById("local-player");
    if (localContainer) localContainer.innerHTML = "";
    const remoteContainer = document.getElementById("remote-player");
    if (remoteContainer) remoteContainer.innerHTML = "";
}

// ==========================================
// 1. CHẾ ĐỘ GỌI 1-1 VỚI AI SENSEI (0 ĐỒNG)
// ==========================================

async function startAiCall() {
    isAiCall = true;
    currentSessionId = null;

    // 1. Chuyển UI sang màn hình Call
    setupPanel.style.display = "none";
    callPanel.style.display = "flex";
    videoContainer.style.display = "flex";
    remotePlayer.style.display = "none";
    aiPlayerScreen.style.display = "flex";
    remoteLabel.innerText = "🤖 AI Sensei (Tanaka)";
    const modeSelect = document.getElementById("ai-mode");
    roomInfo.innerText = "Phòng học 1-1 với AI Sensei (Chế độ: " + (modeSelect ? modeSelect.value : "ROLEPLAY") + ")";

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
    const mode = document.getElementById("ai-mode") ? document.getElementById("ai-mode").value : "ROLEPLAY";
    const scenario = document.getElementById("ai-scenario") ? document.getElementById("ai-scenario").value : "Hội thoại";

    const requestDTO = {
        userMessage: userMessage,
        mode: mode,
        language: "ja",
        scenario: scenario,
        targetLevel: CURRENT_USER_LEVEL || "N5",
        history: aiChatHistory.slice(-4)
    };

    try {
        const headers = { 'Content-Type': 'application/json', ...getCsrfHeaders() };
        const response = await fetch(`/api/ai-tutor/chat?userId=${CURRENT_USER_ID}`, {
            method: 'POST',
            headers: headers,
            body: JSON.stringify(requestDTO)
        });

        if (!response.ok) throw new Error("Lỗi API Backend");
        const data = await response.json();

        aiChatHistory.push({ role: 'user', text: userMessage });
        aiChatHistory.push({ role: 'model', text: data.replyText });

        speakAiResponse(data);

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

    subtitlesOverlay.style.display = "block";
    document.getElementById("sub-jp").innerText = data.replyText;
    document.getElementById("sub-reading").innerText = data.reading ? "📝 " + data.reading : "";
    document.getElementById("sub-vi").innerText = data.translation ? "🇻🇳 " + data.translation : "";

    const utterance = new SpeechSynthesisUtterance(data.replyText);
    utterance.lang = 'ja-JP';
    utterance.rate = 0.95;

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
    const topicTagId = topicTagSelect ? topicTagSelect.value : null;
    const levelTagId = levelTagSelect ? levelTagSelect.value : null;
    const activityTagId = activityTagSelect ? activityTagSelect.value : null;
    currentTagKey = `${topicTagId}:${levelTagId}:${activityTagId}`;

    if (!CURRENT_USER_ID || !topicTagId || !levelTagId || !activityTagId) {
        alert("Vui lòng chọn đủ chủ đề, trình độ và hình thức học!");
        return;
    }

    connectWebSocket(sendJoinRequest);
}

function sendJoinRequest() {
    setupPanel.style.display = "none";
    waitingPanel.style.display = "block";

    stompClient.send("/app/join", {}, JSON.stringify({ 
        'userId': CURRENT_USER_ID,
        'tagKey': currentTagKey,
        'topicTagId': Number(topicTagSelect.value),
        'levelTagId': Number(levelTagSelect.value),
        'activityTagId': Number(activityTagSelect.value),
        'level': CURRENT_USER_LEVEL
    }));
}

function cancelSearch() {
    if (stompClient && stompClient.connected) {
        stompClient.send("/app/cancel-search", {}, JSON.stringify({}));
    }
    showSetupPanel();
}

function endCall() {
    if (isAiCall) {
        isAiCall = false;
        synth.cancel();
        if (recognition) { try { recognition.stop(); } catch(e){} }
        cleanupMedia();
        showSetupPanel();
    } else {
        if (stompClient && stompClient.connected && currentSessionId) {
            stompClient.send("/app/end-call", {}, JSON.stringify({
                'userId': CURRENT_USER_ID
            }));
        }
        if (currentSessionId) {
            fetch(`/api/sessions/${currentSessionId}/leave-agora`, {
                method: 'POST',
                headers: getCsrfHeaders(),
                credentials: 'include'
            }).catch(err => console.error("Leave agora error:", err));
        }
        leaveAgoraCall();
    }
}

// Xử lý gói tin trả về từ Server
function handleMatchMessage(message) {
    if (message.status === "WAITING") {
        console.log("Server báo: Đang đợi đối tác...");
    }
    else if (message.status === "MATCHED") {
        console.log("Server báo: Đã tìm thấy! Vào phòng: " + message.channelName);

        currentSessionId = message.sessionId;
        currentChannelName = message.channelName;
        currentPeerId = message.peerId;

        waitingPanel.style.display = "none";
        callPanel.style.display = "flex";
        videoContainer.style.display = "flex";

        roomInfo.innerText = "Phòng: " + message.channelName + " | Đối tác: " + message.peerUserName + " (ID: " + message.peerId + ")";

        joinAgoraCall(message.channelName, message.sessionId);
    }
    else if (message.status === "RECOVERY_READY") {
        if (recoveryJoinStarted) {
            return;
        }
        recoveryJoinStarted = true;
        currentSessionId = message.sessionId;
        currentChannelName = message.channelName;
        currentPeerId = message.peerId;
        waitingPanel.style.display = "none";
        callPanel.style.display = "flex";
        videoContainer.style.display = "flex";
        roomInfo.innerText = "Room: " + message.channelName + " | Reconnecting to peer";
        joinAgoraCall(message.channelName, message.sessionId);
    }
    else if (message.status === "ACTIVE_SESSION_EXISTS") {
        recoverActiveSession();
    }
    else if (message.status === "PEER_RECONNECTING") {
        roomInfo.innerText = "Peer is reconnecting...";
    }
    else if (message.status === "PEER_RECOVERED") {
        roomInfo.innerText = "Room: " + currentChannelName + " | Peer reconnected";
    }
    else if (message.status === "SESSION_ENDED" || message.status === "NO_ACTIVE_SESSION") {
        alert("The previous call has ended. You can start matchmaking again.");
        showSetupPanel();
    }
    else if (message.status === "PEER_DISCONNECTED") {
        alert("Đối tác đã rời phòng!");
        leaveAgoraCall();
    }
}

// ==========================================
// 3. LOGIC GỌI VIDEO (AGORA SDK)
// ==========================================

async function joinAgoraCall(channelName, sessionId) {
    try {
        const response = await fetch(`/api/sessions/${sessionId}/token`, {
            credentials: 'include'
        });
        if (response.status === 409) {
            throw new Error("The call has ended or the reconnect deadline has passed");
        }
        if (!response.ok) throw new Error("Không thể lấy token từ server");
        const tokenData = await response.json();

        await client.join(AGORA_APP_ID, tokenData.channelName, tokenData.token, tokenData.uid);

        const localTracks = await createAvailableLocalTracks();
        if (localVideoTrack) {
            localVideoTrack.play("local-player");
        }
        if (localTracks.length > 0) {
            await client.publish(localTracks);
        }

        const joinResponse = await fetch(`/api/sessions/${sessionId}/join-agora`, {
            method: 'POST',
            headers: getCsrfHeaders(),
            credentials: 'include'
        });
        if (joinResponse.status === 409) {
            throw new Error("The call has ended or the reconnect deadline has passed");
        }
        if (!joinResponse.ok) {
            throw new Error("Unable to confirm the Agora join");
        }
        if (recoveryInProgress && stompClient && stompClient.connected) {
            recoveryInProgress = false;
            stompClient.send('/app/recovery-complete', {}, JSON.stringify({}));
        }
    } catch (error) {
        console.error("Lỗi khi tham gia Agora:", error);
        alert("Có lỗi xảy ra với camera/mic (Hãy đảm bảo trình duyệt cho phép truy cập): " + error.message);
        if (recoveryInProgress && stompClient && stompClient.connected) {
            stompClient.send('/app/recovery-failed', {}, JSON.stringify({}));
        }
        recoveryInProgress = false;
        recoveryJoinStarted = false;
        leaveAgoraCall();
    }
}

async function createAvailableLocalTracks() {
    const tracks = [];
    const unavailableDevices = [];

    try {
        localAudioTrack = await AgoraRTC.createMicrophoneAudioTrack();
        tracks.push(localAudioTrack);
        document.getElementById("mic-btn").disabled = false;
    } catch (error) {
        localAudioTrack = null;
        unavailableDevices.push("microphone");
        document.getElementById("mic-btn").disabled = true;
        console.warn("Không thể mở microphone:", error);
    }

    try {
        localVideoTrack = await AgoraRTC.createCameraVideoTrack();
        tracks.push(localVideoTrack);
        document.getElementById("cam-btn").disabled = false;
    } catch (error) {
        localVideoTrack = null;
        unavailableDevices.push("camera");
        document.getElementById("cam-btn").disabled = true;
        console.warn("Không thể mở camera:", error);
    }

    if (unavailableDevices.length > 0) {
        alert("Không tìm thấy hoặc không truy cập được " + unavailableDevices.join(" và ")
                + ". Bạn vẫn được kết nối vào phòng với các thiết bị còn khả dụng.");
    }

    return tracks;
}

async function leaveAgoraCall() {
    if (localAudioTrack) { localAudioTrack.close(); localAudioTrack = null; }
    if (localVideoTrack) { localVideoTrack.close(); localVideoTrack = null; }
    const micBtn = document.getElementById("mic-btn");
    if (micBtn) micBtn.disabled = false;
    const camBtn = document.getElementById("cam-btn");
    if (camBtn) camBtn.disabled = false;

    try {
        await client.leave();
    } catch (e) {
        console.warn("Error leaving Agora:", e);
    }
    const localContainer = document.getElementById("local-player");
    if (localContainer) localContainer.innerHTML = "";
    const remoteContainer = document.getElementById("remote-player");
    if (remoteContainer) remoteContainer.innerHTML = "";

    showSetupPanel();
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
