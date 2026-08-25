window.onload = function() {
    // Initialize from Thymeleaf inline variables
    if (typeof CURRENT_USER_ID !== 'undefined' && CURRENT_USER_ID) {
        console.log("Current User ID:", CURRENT_USER_ID);
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
let currentSessionId = null;
let currentChannelName = null;
let currentPeerId = null;
let currentTagKey = null;

// --- GIAO DIỆN (UI PANELS) ---
const setupPanel = document.getElementById("setup-panel");
const waitingPanel = document.getElementById("waiting-panel");
const callPanel = document.getElementById("call-panel");
const videoContainer = document.getElementById("video-container");
const roomInfo = document.getElementById("room-info");
const topicTagSelect = document.getElementById("tag-select-topic");
const levelTagSelect = document.getElementById("tag-select-level");
const activityTagSelect = document.getElementById("tag-select-activity");

// --- GẮN SỰ KIỆN NÚT BẤM ---
document.getElementById("find-partner-btn").addEventListener("click", startSearch);
document.getElementById("cancel-search-btn").addEventListener("click", cancelSearch);
document.getElementById("end-call-btn").addEventListener("click", endCall);
document.getElementById("mic-btn").addEventListener("click", toggleMic);
document.getElementById("cam-btn").addEventListener("click", toggleCam);

// ==========================================
// 1. LOGIC TÌM KIẾM ĐỐI TÁC (MATCHMAKING)
// ==========================================

function startSearch() {
    const topicTagId = topicTagSelect.value;
    const levelTagId = levelTagSelect.value;
    const activityTagId = activityTagSelect.value;
    currentTagKey = `${topicTagId}:${levelTagId}:${activityTagId}`;

    if (!CURRENT_USER_ID || !topicTagId || !levelTagId || !activityTagId) {
        alert("Vui lòng chọn đủ chủ đề, trình độ và hình thức học!");
        return;
    }

    // Nếu chưa kết nối WebSocket thì kết nối trước
    if (!stompClient || !stompClient.connected) {
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);
        
        stompClient.connect({}, function (frame) {
            console.log('Connected WebSocket: ' + frame);
            
            // Lắng nghe kênh cá nhân của User này
            stompClient.subscribe('/topic/match/' + CURRENT_USER_ID, function (message) {
                handleMatchMessage(JSON.parse(message.body));
            });
            
            sendJoinRequest();
        }, function(error) {
            alert("Lỗi kết nối Server! Vui lòng kiểm tra lại kết nối mạng.");
        });
    } else {
        // Đã kết nối thì bắn yêu cầu luôn
        sendJoinRequest();
    }
}

function sendJoinRequest() {
    // Đổi giao diện sang trạng thái Loading
    setupPanel.style.display = "none";
    waitingPanel.style.display = "block";

    // Gửi DTO lên Backend
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
        stompClient.send("/app/cancel-search", {}, JSON.stringify({ 
            'userId': CURRENT_USER_ID,
            'tagKey': currentTagKey 
        }));
    }
    // Trả giao diện về ban đầu
    waitingPanel.style.display = "none";
    setupPanel.style.display = "block";
    currentSessionId = null;
    currentChannelName = null;
    currentPeerId = null;
    currentTagKey = null;
}

function endCall() {
    if (stompClient && stompClient.connected && currentSessionId) {
        stompClient.send("/app/end-call", {}, JSON.stringify({ 
            'userId': CURRENT_USER_ID
        }));
    }
    // Gọi API leave-agora để backend cập nhật timestamp
    if (currentSessionId) {
        fetch(`/api/sessions/${currentSessionId}/leave-agora`, {
            method: 'POST',
            credentials: 'include'
        }).catch(err => console.error("Leave agora error:", err));
    }
    leaveAgoraCall();
}

// Xử lý gói tin trả về từ Server
function handleMatchMessage(message) {
    if (message.status === "WAITING") {
        console.log("Server báo: Đang đợi đối tác...");
    } 
    else if (message.status === "MATCHED") {
        console.log("Server báo: Đã tìm thấy! Vào phòng: " + message.channelName);

        // Lưu session info
        currentSessionId = message.sessionId;
        currentChannelName = message.channelName;
        currentPeerId = message.peerId;
        
        // Chuyển giao diện sang Màn hình Gọi
        waitingPanel.style.display = "none";
        callPanel.style.display = "block";
        videoContainer.style.display = "flex"; 
        
        roomInfo.innerText = "Phòng: " + message.channelName + " | Đối tác: " + message.peerUserName + " (ID: " + message.peerId + ")";
        
        // Kích hoạt camera và join Agora
        joinAgoraCall(message.channelName, message.sessionId);
    } 
    else if (message.status === "PEER_DISCONNECTED") {
        alert("Đối tác đã rời phòng!");
        leaveAgoraCall();
    }
}

// ==========================================
// 2. LOGIC GỌI VIDEO (AGORA SDK)
// ==========================================

async function joinAgoraCall(channelName, sessionId) {
    try {
        // Lấy Token từ Backend qua session API
        const response = await fetch(`/api/sessions/${sessionId}/token`, {
            credentials: 'include'
        });
        if (!response.ok) throw new Error("Không thể lấy token từ server");
        const tokenData = await response.json();

        // Join phòng Agora
        await client.join(AGORA_APP_ID, tokenData.channelName, tokenData.token, tokenData.uid);

        // Mở mic/camera độc lập để thiếu một thiết bị không làm hỏng toàn bộ cuộc gọi
        const localTracks = await createAvailableLocalTracks();
        if (localVideoTrack) {
            localVideoTrack.play("local-player");
        }
        if (localTracks.length > 0) {
            await client.publish(localTracks);
        }

        // Báo cho backend biết đã join Agora thành công
        await fetch(`/api/sessions/${sessionId}/join-agora`, {
            method: 'POST',
            credentials: 'include'
        });

    } catch (error) {
        console.error("Lỗi khi tham gia Agora:", error);
        alert("Có lỗi xảy ra với camera/mic (Hãy đảm bảo trình duyệt cho phép truy cập): " + error.message);
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
    // Tắt các luồng thiết bị
    if (localAudioTrack) { localAudioTrack.close(); localAudioTrack = null; }
    if (localVideoTrack) { localVideoTrack.close(); localVideoTrack = null; }
    document.getElementById("mic-btn").disabled = false;
    document.getElementById("cam-btn").disabled = false;

    // Thoát Agora
    try {
        await client.leave();
    } catch (e) {
        console.warn("Error leaving Agora:", e);
    }
    document.getElementById("local-player").innerHTML = "";
    document.getElementById("remote-player").innerHTML = "";

    // Trả giao diện về ban đầu
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    setupPanel.style.display = "block";

    // Reset state
    currentSessionId = null;
    currentChannelName = null;
    currentPeerId = null;
    currentTagKey = null;
}

function toggleMic() {
    if (localAudioTrack) {
        isAudioMuted = !isAudioMuted;
        localAudioTrack.setMuted(isAudioMuted);
        document.getElementById("mic-btn").innerText = isAudioMuted ? "Bật Mic" : "Tắt Mic";
    }
}

function toggleCam() {
    if (localVideoTrack) {
        isVideoMuted = !isVideoMuted;
        localVideoTrack.setMuted(isVideoMuted);
        document.getElementById("cam-btn").innerText = isVideoMuted ? "Bật Camera" : "Tắt Camera";
    }
}

// Lắng nghe sự kiện đối tác (Remote) bật camera
client.on("user-published", async (user, mediaType) => {
    await client.subscribe(user, mediaType);
    if (mediaType === "video") {
        user.videoTrack.play("remote-player");
    }
    if (mediaType === "audio") {
        user.audioTrack.play();
    }
});

// Lắng nghe sự kiện đối tác (Remote) tắt camera
client.on("user-unpublished", (user) => {
    document.getElementById("remote-player").innerHTML = "";
});
