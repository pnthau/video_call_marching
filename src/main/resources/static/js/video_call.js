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

// --- GIAO DIỆN (UI PANELS) ---
const setupPanel = document.getElementById("setup-panel");
const waitingPanel = document.getElementById("waiting-panel");
const callPanel = document.getElementById("call-panel");
const videoContainer = document.getElementById("video-container");
const roomInfo = document.getElementById("room-info");

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
    currentUserId = document.getElementById("uid").value;
    currentTagKey = document.getElementById("tag-key").value;

    if (!currentUserId || !currentTagKey) {
        alert("Vui lòng nhập ID và Tag!");
        return;
    }

    // Nếu chưa kết nối WebSocket thì kết nối trước
    if (!stompClient || !stompClient.connected) {
        const socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);
        
        // stompClient.debug = null; // Bật dòng này nếu muốn ẩn log ping/pong

        stompClient.connect({}, function (frame) {
            console.log('Connected WebSocket: ' + frame);
            
            // Lắng nghe kênh cá nhân của User này
            stompClient.subscribe('/topic/match/' + currentUserId, function (message) {
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
    
    // Gửi DTO lên Backend (Ép kiểu userId thành số nguyên để Java không bị lỗi Null)
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
    // Trả giao diện về ban đầu
    waitingPanel.style.display = "none";
    setupPanel.style.display = "block";
}

function endCall() {
    if (stompClient && stompClient.connected) {
        stompClient.send("/app/end-call", {}, JSON.stringify({ 
            'userId': parseInt(currentUserId), 
            'tagKey': currentTagKey 
        }));
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
        
        // Chuyển giao diện sang Màn hình Gọi
        waitingPanel.style.display = "none";
        callPanel.style.display = "block";
        videoContainer.style.display = "flex"; 
        
        roomInfo.innerText = "Phòng: " + message.channelName + " | Đối tác: " + message.peerUserName + " (ID: " + message.peerId + ")";
        
        // Kích hoạt camera và join Agora
        joinAgoraCall(message.channelName, currentUserId);
    } 
    else if (message.status === "PEER_DISCONNECTED") {
        alert("Đối tác đã rời phòng!");
        leaveAgoraCall();
    }
}

// ==========================================
// 2. LOGIC GỌI VIDEO (AGORA SDK)
// ==========================================

async function joinAgoraCall(channelName, uid) {
    try {
        // Lấy Token từ Backend
        const response = await fetch(`/api/agora/token?channelName=${channelName}&uid=${uid}`);
        if (!response.ok) throw new Error("Không thể lấy token từ server");
        const token = await response.text();

        // Join phòng Agora
        await client.join(AGORA_APP_ID, channelName, token, uid);

        // Mở Camera và Mic
        const tracks = await AgoraRTC.createMicrophoneAndCameraTracks();
        localAudioTrack = tracks[0];
        localVideoTrack = tracks[1];

        // Phát video Local
        localVideoTrack.play("local-player");
        
        // Bắn luồng Media lên Agora
        await client.publish([localAudioTrack, localVideoTrack]);

    } catch (error) {
        console.error("Lỗi khi tham gia Agora:", error);
        alert("Có lỗi xảy ra với camera/mic (Hãy đảm bảo trình duyệt cho phép truy cập): " + error.message);
    }
}

async function leaveAgoraCall() {
    // Tắt các luồng thiết bị
    if (localAudioTrack) { localAudioTrack.close(); localAudioTrack = null; }
    if (localVideoTrack) { localVideoTrack.close(); localVideoTrack = null; }
    
    // Thoát Agora
    await client.leave();
    document.getElementById("local-player").innerHTML = "";
    document.getElementById("remote-player").innerHTML = "";
    
    // Trả giao diện về ban đầu
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    setupPanel.style.display = "block";
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
