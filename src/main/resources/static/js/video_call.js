const client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
let localAudioTrack = null;
let localVideoTrack = null;
let isAudioMuted = false;
let isVideoMuted = false;

document.getElementById("join-btn").addEventListener("click", joinCall);
document.getElementById("leave-btn").addEventListener("click", leaveCall);
document.getElementById("mic-btn").addEventListener("click", toggleMic);
document.getElementById("cam-btn").addEventListener("click", toggleCam);

async function joinCall() {
    const channelName = document.getElementById("channel").value;
    const uid = document.getElementById("uid").value;

    if (!channelName || !uid) {
        alert("Vui lòng nhập Channel Name và UID (ví dụ: room1 và 1)!");
        return;
    }

    try {
        const response = await fetch(`/api/agora/token?channelName=${channelName}&uid=${uid}`);
        if (!response.ok) throw new Error("Không thể lấy token từ server");
        const token = await response.text();

        await client.join(AGORA_APP_ID, channelName, token, uid);

        const tracks = await AgoraRTC.createMicrophoneAndCameraTracks();
        localAudioTrack = tracks[0];
        localVideoTrack = tracks[1];

        localVideoTrack.play("local-player");

        await client.publish([localAudioTrack, localVideoTrack]);

        document.getElementById("join-btn").disabled = true;
        document.getElementById("leave-btn").disabled = false;
        document.getElementById("mic-btn").disabled = false;
        document.getElementById("cam-btn").disabled = false;
        
    } catch (error) {
        console.error("Lỗi khi tham gia:", error);
        alert("Có lỗi xảy ra: " + error.message);
    }
}

async function leaveCall() {
    if (localAudioTrack) localAudioTrack.close();
    if (localVideoTrack) localVideoTrack.close();
    await client.leave();
    
    document.getElementById("local-player").innerHTML = "";
    document.getElementById("remote-player").innerHTML = "";
    
    document.getElementById("join-btn").disabled = false;
    document.getElementById("leave-btn").disabled = true;
    document.getElementById("mic-btn").disabled = true;
    document.getElementById("cam-btn").disabled = true;
}

function toggleMic() {
    if (isAudioMuted) {
        localAudioTrack.setMuted(false);
        isAudioMuted = false;
        document.getElementById("mic-btn").innerText = "Tắt Mic";
    } else {
        localAudioTrack.setMuted(true);
        isAudioMuted = true;
        document.getElementById("mic-btn").innerText = "Bật Mic";
    }
}

function toggleCam() {
    if (isVideoMuted) {
        localVideoTrack.setMuted(false);
        isVideoMuted = false;
        document.getElementById("cam-btn").innerText = "Tắt Camera";
    } else {
        localVideoTrack.setMuted(true);
        isVideoMuted = true;
        document.getElementById("cam-btn").innerText = "Bật Camera";
    }
}

client.on("user-published", async (user, mediaType) => {
    await client.subscribe(user, mediaType);
    if (mediaType === "video") {
        user.videoTrack.play("remote-player");
    }
    if (mediaType === "audio") {
        user.audioTrack.play();
    }
});

client.on("user-unpublished", (user) => {
    document.getElementById("remote-player").innerHTML = "";
});
