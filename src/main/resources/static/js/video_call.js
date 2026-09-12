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

let currentP2PLanguage = 'ja';

function switchP2PLanguage(lang, targetLevelTagId, targetTopicTagId, targetActivityTagId, targetLevelText) {
    currentP2PLanguage = lang;
    const jaLabel = document.getElementById("label-lang-ja");
    const enLabel = document.getElementById("label-lang-en");
    const jaRadio = document.querySelector('input[name="p2p-language"][value="ja"]');
    const enRadio = document.querySelector('input[name="p2p-language"][value="en"]');

    if (lang === 'en') {
        if (enLabel) enLabel.classList.add("active");
        if (jaLabel) jaLabel.classList.remove("active");
        if (enRadio) enRadio.checked = true;
    } else {
        if (jaLabel) jaLabel.classList.add("active");
        if (enLabel) enLabel.classList.remove("active");
        if (jaRadio) jaRadio.checked = true;
    }

    populateTagsForLanguage(lang, targetLevelTagId, targetTopicTagId, targetActivityTagId, targetLevelText);
}
window.switchP2PLanguage = switchP2PLanguage;

function populateTagsForLanguage(lang, targetLevelTagId, targetTopicTagId, targetActivityTagId, targetLevelText) {
    const tags = (typeof ALL_AVAILABLE_TAGS !== 'undefined' && Array.isArray(ALL_AVAILABLE_TAGS)) ? ALL_AVAILABLE_TAGS : [];
    if (!levelTagSelect || !topicTagSelect || !activityTagSelect) return;

    // 1. Lọc Level Tags theo Ngôn ngữ
    const levelTags = tags.filter(t => t.categoryType === 'LEVEL');
    let filteredLevels = [];
    if (lang === 'en') {
        filteredLevels = levelTags.filter(t => /^[ABC][12]/i.test(t.name) || t.name.toLowerCase().includes('tiếng anh') || t.name.toLowerCase().includes('cefr'));
        if (filteredLevels.length === 0) filteredLevels = levelTags;
    } else {
        filteredLevels = levelTags.filter(t => /^N[1-5]/i.test(t.name) || t.name.toLowerCase().includes('jlpt'));
        if (filteredLevels.length === 0) filteredLevels = levelTags;
    }

    levelTagSelect.innerHTML = '<option value="">-- Chọn trình độ --</option>';
    let selectedLevelOption = false;
    filteredLevels.forEach(tag => {
        const opt = document.createElement('option');
        opt.value = tag.id;
        opt.textContent = tag.name;
        if (targetLevelTagId && String(tag.id) === String(targetLevelTagId)) {
            opt.selected = true;
            selectedLevelOption = true;
        } else if (!selectedLevelOption && targetLevelText && tag.name.toUpperCase().startsWith(targetLevelText.toUpperCase())) {
            opt.selected = true;
            selectedLevelOption = true;
        }
        levelTagSelect.appendChild(opt);
    });
    if (!selectedLevelOption && levelTagSelect.options.length > 1) {
        const userLvl = (typeof CURRENT_USER_LEVEL !== 'undefined' && CURRENT_USER_LEVEL) ? CURRENT_USER_LEVEL.toUpperCase() : '';
        let foundUserLvl = false;
        for (let i = 1; i < levelTagSelect.options.length; i++) {
            if (userLvl && levelTagSelect.options[i].text.toUpperCase().includes(userLvl)) {
                levelTagSelect.selectedIndex = i;
                foundUserLvl = true;
                break;
            }
        }
        if (!foundUserLvl) {
            levelTagSelect.selectedIndex = 1;
        }
    }

    // 2. Lọc Topic Tags theo Ngôn ngữ
    const topicTags = tags.filter(t => t.categoryType === 'TOPIC');
    let filteredTopics = [];
    if (lang === 'en') {
        filteredTopics = topicTags.filter(t => t.name.toLowerCase().includes('tiếng anh') || t.name.toLowerCase().includes('english'));
        topicTags.forEach(t => {
            if (!filteredTopics.some(ft => ft.id === t.id)) {
                filteredTopics.push(t);
            }
        });
    } else {
        filteredTopics = topicTags.filter(t => !t.name.toLowerCase().includes('tiếng anh') && !t.name.toLowerCase().includes('english'));
        if (filteredTopics.length === 0) filteredTopics = topicTags;
    }

    topicTagSelect.innerHTML = '<option value="">-- Chọn chủ đề --</option>';
    let selectedTopicOption = false;
    filteredTopics.forEach(tag => {
        const opt = document.createElement('option');
        opt.value = tag.id;
        opt.textContent = tag.name;
        if (targetTopicTagId && String(tag.id) === String(targetTopicTagId)) {
            opt.selected = true;
            selectedTopicOption = true;
        }
        topicTagSelect.appendChild(opt);
    });
    if (!selectedTopicOption && topicTagSelect.options.length > 1) {
        topicTagSelect.selectedIndex = 1;
    }

    // 3. Lọc Activity Tags
    const activityTags = tags.filter(t => t.categoryType === 'ACTIVITY');
    activityTagSelect.innerHTML = '<option value="">-- Chọn hình thức --</option>';
    let selectedActivityOption = false;
    activityTags.forEach(tag => {
        const opt = document.createElement('option');
        opt.value = tag.id;
        opt.textContent = tag.name;
        if (targetActivityTagId && String(tag.id) === String(targetActivityTagId)) {
            opt.selected = true;
            selectedActivityOption = true;
        }
        activityTagSelect.appendChild(opt);
    });
    if (!selectedActivityOption && activityTagSelect.options.length > 1) {
        activityTagSelect.selectedIndex = 1;
    }
}

function initP2PSelectionFromUrl() {
    const urlParams = new URLSearchParams(window.location.search);
    const qLang = urlParams.get('language');
    const qLevel = urlParams.get('level');
    const qLevelTagId = urlParams.get('levelTagId');
    const qTopicTagId = urlParams.get('topicTagId');
    const qActivityTagId = urlParams.get('activityTagId');
    const qAutoJoin = urlParams.get('autoJoin');

    let resolvedLang = 'ja';
    if (qLang && (qLang.toLowerCase() === 'en' || qLang.toLowerCase() === 'ja')) {
        resolvedLang = qLang.toLowerCase();
    } else if (qLevel && /^[ABC][12]/i.test(qLevel)) {
        resolvedLang = 'en';
    }

    switchP2PLanguage(resolvedLang, qLevelTagId, qTopicTagId, qActivityTagId, qLevel);

    if (qAutoJoin === 'true') {
        console.log('🚀 Chờ hệ thống kiểm tra trạng thái phiên trước khi tự động tìm phòng...');
        let retries = 0;
        const maxRetries = 25; // 25 * 200ms = 5 giây
        const checkInterval = setInterval(() => {
            retries++;
            const sPanel = document.getElementById('setup-panel');
            const wPanel = document.getElementById('waiting-panel');
            const findBtn = document.getElementById('find-partner-btn');

            if (wPanel && wPanel.style.display !== 'none' && 
                wPanel.innerText && wPanel.innerText.includes('RECOVERING')) {
                console.log('⚠️ Phát hiện phiên gọi trước đang khôi phục, hủy autoJoin mới.');
                clearInterval(checkInterval);
                return;
            }

            if (sPanel && sPanel.style.display !== 'none' && findBtn) {
                clearInterval(checkInterval);
                console.log('✅ Hệ thống sẵn sàng, tự động tìm kiếm đối tác thực chiến!');
                findBtn.click();
            } else if (retries >= maxRetries) {
                clearInterval(checkInterval);
                console.warn('⏱️ Hết thời gian chờ hệ thống, vui lòng bấm Tìm kiếm thủ công.');
            }
        }, 200);
    }
}

// --- GẮN SỰ KIỆN NÚT BẤM ---

document.getElementById("find-partner-btn")?.addEventListener("click", startSearch);
document.getElementById("cancel-search-btn")?.addEventListener("click", cancelSearch);
document.getElementById("end-call-btn")?.addEventListener("click", endCall);
document.getElementById("mic-btn")?.addEventListener("click", toggleMic);
document.getElementById("cam-btn")?.addEventListener("click", toggleCam);
window.addEventListener("load", recoverActiveSession);
window.addEventListener("DOMContentLoaded", initP2PSelectionFromUrl);

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
    const csDrawer = document.getElementById("cheatsheet-drawer");
    if (csDrawer) csDrawer.style.display = "none";
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
// 2. LOGIC TÌM KIẾM ĐỐI TÁC NGƯỜI THẬT (P2P)
// ==========================================

function startSearch() {
    if (!CURRENT_USER_ID || CURRENT_USER_ID === 0) {
        window.location.href = '/login';
        return;
    }
    
    const topicTagId = topicTagSelect ? topicTagSelect.value : null;
    const levelTagId = levelTagSelect ? levelTagSelect.value : null;
    const activityTagId = activityTagSelect ? activityTagSelect.value : null;
    currentTagKey = `${topicTagId}:${levelTagId}:${activityTagId}`;

    if (!topicTagId || !levelTagId || !activityTagId) {
        alert("Vui lòng chọn đủ chủ đề, trình độ và hình thức học!");
        return;
    }

    connectWebSocket(sendJoinRequest);
}

function sendJoinRequest() {
    setupPanel.style.display = "none";
    waitingPanel.style.display = "block";

    let levelEnum = "N5";
    if (levelTagSelect && levelTagSelect.selectedOptions && levelTagSelect.selectedOptions[0]) {
        const txt = levelTagSelect.selectedOptions[0].text.trim().toUpperCase();
        if (txt.includes("N5")) levelEnum = "N5";
        else if (txt.includes("N4")) levelEnum = "N4";
        else if (txt.includes("N3")) levelEnum = "N3";
        else if (txt.includes("N2")) levelEnum = "N2";
        else if (txt.includes("N1")) levelEnum = "N1";
        else levelEnum = (typeof CURRENT_USER_LEVEL !== 'undefined' && CURRENT_USER_LEVEL) ? CURRENT_USER_LEVEL : "N5";
    }

    stompClient.send("/app/join", {}, JSON.stringify({ 
        'userId': CURRENT_USER_ID,
        'tagKey': currentTagKey,
        'topicTagId': Number(topicTagSelect.value),
        'levelTagId': Number(levelTagSelect.value),
        'activityTagId': Number(activityTagSelect.value),
        'level': levelEnum
    }));
}

function cancelSearch() {
    if (stompClient && stompClient.connected) {
        stompClient.send("/app/cancel-search", {}, JSON.stringify({}));
    }
    showSetupPanel();
}

function endCall() {
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
    leaveAgoraCall(true);
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
        if (currentSessionId && currentPeerId) {
            showRatingModal();
        } else {
            showSetupPanel();
        }
    }
    else if (message.status === "PEER_DISCONNECTED") {
        alert("Đối tác đã rời phòng!");
        leaveAgoraCall(true);
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

async function leaveAgoraCall(showRating = false) {
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

    if (showRating && currentSessionId && currentPeerId) {
        showRatingModal();
    } else {
        showSetupPanel();
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

function toggleCheatSheet() {
    const drawer = document.getElementById("cheatsheet-drawer");
    if (drawer) {
        drawer.style.display = (drawer.style.display === "none" || drawer.style.display === "") ? "flex" : "none";
    }
}

// ==========================================
// 4. LOGIC ĐÁNH GIÁ (RATING)
// ==========================================

let currentScores = {};

async function showRatingModal() {
    setupPanel.style.display = "none";
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    if (feedbackCard) feedbackCard.style.display = "none";
    
    const ratingModal = document.getElementById("rating-modal");
    if (!ratingModal) return;
    
    ratingModal.style.display = "block";
    const ratingComment = document.getElementById("rating-comment");
    if (ratingComment) ratingComment.value = "";
    currentScores = {};

    try {
        const response = await fetch("/api/peer-ratings/rubrics");
        const rubrics = await response.json();
        
        const container = document.getElementById("rating-criteria-container");
        if (container) {
            container.innerHTML = "";
            
            rubrics.forEach(rubric => {
                currentScores[rubric.criteria] = 0;
                
                const div = document.createElement("div");
                div.style.marginBottom = "15px";
                div.style.padding = "10px";
                div.style.background = "#334155";
                div.style.borderRadius = "5px";
                
                div.innerHTML = `
                    <div style="font-weight: bold; margin-bottom: 5px; font-size: 16px;">${rubric.displayName}</div>
                    <div style="font-size: 13px; color: #cbd5e1; margin-bottom: 8px;">${rubric.description || ''}</div>
                    <div class="star-rating" data-criteria="${rubric.criteria}" style="font-size: 28px; cursor: pointer; user-select: none;">
                        <span data-value="1" style="color: #64748b;">★</span>
                        <span data-value="2" style="color: #64748b;">★</span>
                        <span data-value="3" style="color: #64748b;">★</span>
                        <span data-value="4" style="color: #64748b;">★</span>
                        <span data-value="5" style="color: #64748b;">★</span>
                    </div>
                `;
                container.appendChild(div);
                
                const stars = div.querySelectorAll(".star-rating span");
                stars.forEach(star => {
                    star.addEventListener("click", function() {
                        const val = parseInt(this.getAttribute("data-value"));
                        currentScores[rubric.criteria] = val;
                        stars.forEach(s => {
                            if (parseInt(s.getAttribute("data-value")) <= val) {
                                s.style.color = "#fbbf24";
                            } else {
                                s.style.color = "#64748b";
                            }
                        });
                    });
                });
            });
        }
    } catch (e) {
        console.error("Error fetching rubrics:", e);
    }
}

document.getElementById("skip-rating-btn")?.addEventListener("click", () => {
    document.getElementById("rating-modal").style.display = "none";
    showSetupPanel();
});

document.getElementById("submit-rating-btn")?.addEventListener("click", async () => {
    const hasScore = Object.values(currentScores).some(v => v > 0);
    if (!hasScore) {
        alert("Vui lòng đánh giá ít nhất 1 tiêu chí hoặc bấm Bỏ qua.");
        return;
    }

    const payload = {
        sessionId: currentSessionId,
        rateeId: currentPeerId,
        scores: currentScores,
        comment: document.getElementById("rating-comment") ? document.getElementById("rating-comment").value : ""
    };

    try {
        const response = await fetch("/api/peer-ratings", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                ...getCsrfHeaders()
            },
            body: JSON.stringify(payload)
        });
        
        if (response.ok) {
            alert("Cảm ơn bạn đã gửi đánh giá!");
        } else {
            console.warn("Rating submission failed");
            alert("Không thể gửi đánh giá, vui lòng thử lại sau.");
        }
    } catch (e) {
        console.error("Error submitting rating:", e);
    }
    
    document.getElementById("rating-modal").style.display = "none";
    showSetupPanel();
});
