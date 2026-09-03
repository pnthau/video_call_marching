function createDeviceControls({ AgoraRTC, reliability, getClient, getTrack, replaceTrack, getRemoteAudioTracks, onState }) {
    const selected = { microphone: null, camera: null, playback: null };
    const devices = { microphone: [], camera: [], playback: [] };
    const preview = { microphone: null, camera: null };
    const queues = new Map();
    const moduleHandlers = [];
    let consented = false;

    const closeTrack = track => {
        if (!track || track.__deviceControlClosed) return;
        track.__deviceControlClosed = true;
        try { if (typeof track.stop === "function") track.stop(); } catch (_) {}
        try { if (typeof track.close === "function") track.close(); } catch (_) {}
    };
    const normalized = list => (Array.isArray(list) ? list : []).filter(item => item && item.deviceId)
        .slice().sort((a, b) => String(a.deviceId).localeCompare(String(b.deviceId)));
    const errorCode = error => {
        const code = error && error.code;
        if (code === "PERMISSION_DENIED") return "PERMISSION_DENIED";
        if (code === "DEVICE_NOT_FOUND" || code === "ENUMERATE_DEVICES_FAILED") return "DEVICE_MISSING";
        if (code === "NOT_READABLE") return "DEVICE_BUSY";
        if (code === "NOT_SUPPORTED" || code === "WEB_SECURITY_RESTRICT" || code === "CONSTRAINT_NOT_SATISFIED") return "UNSUPPORTED";
        return "DEVICE_IO_ERROR";
    };
    const serial = (kind, operation) => {
        const previous = queues.get(kind) || Promise.resolve();
        const next = previous.catch(() => {}).then(operation);
        // Keep bookkeeping rejection-safe: the operation's rejection is handled by
        // its caller, while the queue marker must never become an unhandled reject.
        let marker;
        marker = next.then(() => {
            if (queues.get(kind) === marker) queues.delete(kind);
        }, () => {
            if (queues.get(kind) === marker) queues.delete(kind);
        });
        queues.set(kind, marker);
        return next;
    };
    const factory = (kind, deviceId) => {
        const config = deviceId ? (kind === "microphone" ? { microphoneId: deviceId } : { cameraId: deviceId }) : undefined;
        return kind === "microphone"
            ? AgoraRTC.createMicrophoneAudioTrack(config)
            : AgoraRTC.createCameraVideoTrack(config);
    };
    const fallback = kind => devices[kind][0] ? devices[kind][0].deviceId : null;

    async function enumerate(useCachedPermissions) {
        devices.microphone = normalized(await AgoraRTC.getMicrophones(useCachedPermissions));
        devices.camera = normalized(await AgoraRTC.getCameras(useCachedPermissions));
        devices.playback = typeof AgoraRTC.getPlaybackDevices === "function"
            ? normalized(await AgoraRTC.getPlaybackDevices(useCachedPermissions)) : [];
        selected.microphone = devices.microphone.some(item => item.deviceId === selected.microphone) ? selected.microphone : fallback("microphone");
        selected.camera = devices.camera.some(item => item.deviceId === selected.camera) ? selected.camera : fallback("camera");
        selected.playback = devices.playback.some(item => item.deviceId === selected.playback) ? selected.playback : fallback("playback");
        return snapshot();
    }

    async function replaceLocal(kind, deviceId, context) {
        const oldTrack = getTrack(kind);
        if (!oldTrack || !reliability.isDeviceContextCurrent(context, oldTrack)) return null;
        const replacement = await reliability.ordinary(kind === "microphone" ? "CREATE_MIC_TRACK" : "CREATE_CAMERA_TRACK",
            `device-replacement:${kind}`, () => factory(kind, deviceId), () => {}, false, {
                preserveFailureState: true,
                isCurrent: () => getTrack(kind) === oldTrack && reliability.isDeviceContextCurrent(context, oldTrack)
            });
        if (!replacement || !reliability.isDeviceContextCurrent(context, oldTrack) || getTrack(kind) !== oldTrack) {
            reliability.cleanupResource({ operationId: replacement && replacement.__ownerOperationId }, replacement);
            return null;
        }
        const client = getClient();
        return reliability.shared("DEVICE_SWITCH", `device-replace:${kind}`, client,
            async exact => {
                if (exact.connectionState === "CONNECTED") await exact.unpublish([oldTrack]);
                if (exact.connectionState === "CONNECTED") await exact.publish([replacement]);
                return replacement;
            },
            async (exact, op, accepted) => accepted && exact === getClient()
                && getTrack(kind) === oldTrack && reliability.isDeviceContextCurrent(context, oldTrack),
            () => {
                replaceTrack(kind, replacement, oldTrack);
                selected[kind] = deviceId;
                reliability.cleanupResource({ operationId: oldTrack.__ownerOperationId }, oldTrack);
                onState({ kind, state: "SWITCHED", selected: deviceId });
            }, false,
            async (exact, op) => {
                reliability.cleanupResource({ operationId: replacement.__ownerOperationId }, replacement);
                if (oldTrack && exact === getClient() && exact.connectionState === "CONNECTED"
                    && reliability.isDeviceContextCurrent(context, oldTrack)) {
                    try { await exact.publish([oldTrack]); } catch (error) { reliability.diagnostic(op, error, true); }
                }
            }, {
                preserveFailureState: true,
                isCurrent: exact => exact === getClient() && getTrack(kind) === oldTrack
                    && reliability.isDeviceContextCurrent(context, oldTrack)
            });
    }

    async function switchLocal(kind, deviceId) {
        const current = getTrack(kind);
        const context = reliability.captureDeviceContext(current);
        return serial(kind, async () => {
            const valid = devices[kind].some(item => item.deviceId === deviceId);
            if (!valid) throw Object.assign(new Error("device missing"), { code: "DEVICE_NOT_FOUND" });
            if (!current) {
                if (!reliability.isDeviceContextCurrent(context, current)) return null;
                selected[kind] = deviceId;
                onState({ kind, state: "SELECTED", selected: deviceId });
                return null;
            }
            if (!reliability.isDeviceContextCurrent(context, current) || getTrack(kind) !== current) return null;
            if (typeof current.setDevice !== "function") return replaceLocal(kind, deviceId, context);
            const switched = await reliability.shared("DEVICE_SWITCH", `device:${kind}`, current,
                exact => exact.setDevice(deviceId),
                async (exact, op, accepted) => accepted && exact === getTrack(kind)
                    && reliability.isDeviceContextCurrent(context, exact),
                () => {
                    selected[kind] = deviceId;
                    onState({ kind, state: "SWITCHED", selected: deviceId });
                }, false, null, {
                    mode: "PRESERVE_CURRENT_ON_REJECT",
                    preserveFailureState: true,
                    isCurrent: exact => exact === getTrack(kind)
                        && reliability.isDeviceContextCurrent(context, exact)
                });
            return switched === null ? replaceLocal(kind, deviceId, context) : current;
        });
    }

    async function switchPlayback(deviceId) {
        return serial("playback", async () => {
            const context = reliability.captureDeviceContext(null);
            const previousSelected = selected.playback;
            if (!devices.playback.some(item => item.deviceId === deviceId)) throw Object.assign(new Error("device missing"), { code: "DEVICE_NOT_FOUND" });
            const tracks = [...getRemoteAudioTracks()].filter(track => track && typeof track.setPlaybackDevice === "function");
            if (!tracks.length) {
                onState({ kind: "playback", state: "DEFAULT_OUTPUT" });
                return false;
            }
            await Promise.all(tracks.map(track => track.setPlaybackDevice(deviceId)));
            if (!reliability.isDeviceContextCurrent(context, null)) {
                selected.playback = previousSelected;
                return false;
            }
            selected.playback = deviceId;
            onState({ kind: "playback", state: "SWITCHED", selected: deviceId });
            return true;
        });
    }

    async function recoverChanged(kind) {
        const context = reliability.captureDeviceContext(getTrack(kind));
        let previouslySelected = selected[kind];
        try {
            await enumerate(consented);
            const next = fallback(kind);
            if (kind === "camera" && !next) {
                const capturedVideoTrack = context.trackRef;
                const capturedClient = context.clientRef;
                const valid = () => !!capturedVideoTrack && !reliability.terminalOrLeaveLatch
                    && getTrack("camera") === capturedVideoTrack
                    && reliability.mediaGeneration === context.mediaGeneration
                    && reliability.cleanupGeneration === context.cleanupGeneration
                    && reliability.clientGeneration === context.clientGeneration;
                if (!valid()) return;
                await reliability.shared("DEVICE_SWITCH", "device:camera", capturedVideoTrack,
                    exact => capturedClient.connectionState === "CONNECTED" ? capturedClient.unpublish([exact]) : true,
                    async (exact, op, accepted) => accepted && exact === capturedVideoTrack
                        && getTrack("camera") === capturedVideoTrack
                        && valid(),
                    () => {
                        if (!valid()) return;
                        reliability.cleanupResource({ operationId: capturedVideoTrack.__ownerOperationId }, capturedVideoTrack);
                        replaceTrack("camera", null, capturedVideoTrack);
                        onState({ kind: "camera", state: "AUDIO_ONLY", code: "DEVICE_MISSING" });
                    }, false, null, { preserveFailureState: true,
                        isCurrent: exact => exact === capturedVideoTrack && getTrack("camera") === capturedVideoTrack
                            && valid() });
                return;
            }
            if (!reliability.isDeviceContextCurrent(context, getTrack(kind))
                && !(kind === "camera" && getTrack(kind) === context.trackRef)) {
                selected[kind] = previouslySelected;
                return;
            }
            if (previouslySelected && devices[kind].some(item => item.deviceId === previouslySelected)) {
                selected[kind] = previouslySelected;
                return;
            }
            if (!next) {
                if (kind === "camera") {
                    const current = getTrack("camera");
                    if (current) {
                        const capturedClient = context.clientRef;
                        await reliability.shared("DEVICE_SWITCH", "device:camera", current,
                            exact => capturedClient.connectionState === "CONNECTED" ? capturedClient.unpublish([exact]) : true,
                            async (exact, op, accepted) => accepted && exact === current
                                && getTrack("camera") === current,
                            () => {
                                reliability.cleanupResource({ operationId: current.__ownerOperationId }, current);
                                replaceTrack("camera", null, current);
                                onState({ kind: "camera", state: "AUDIO_ONLY", code: "DEVICE_MISSING" });
                            }, false, null, { preserveFailureState: true,
                                isCurrent: exact => exact === current && getTrack("camera") === current
                                    });
                    } else onState({ kind: "camera", state: "AUDIO_ONLY", code: "DEVICE_MISSING" });
                } else onState({ kind: kind === "microphone" ? "microphone" : kind,
                    state: kind === "microphone" ? "BLOCKED" : "DEFAULT_OUTPUT", code: "DEVICE_MISSING" });
                return;
            }
            // Enumeration may select a newly visible fallback before the switch
            // completes; keep the old selection until ownership is committed.
            selected[kind] = previouslySelected;
            if (kind === "playback") await switchPlayback(next);
            else {
                await switchLocal(kind, next);
                if (getTrack(kind) === context.trackRef
                    && !reliability.isDeviceContextCurrent(context, getTrack(kind))) return;
            }
        } catch (error) {
            selected[kind] = previouslySelected;
            onState({ kind, state: kind === "microphone" ? "BLOCKED" : "AUDIO_ONLY", code: errorCode(error) });
        }
    }

    function bindModuleEvents() {
        if (!AgoraRTC || typeof AgoraRTC.on !== "function" || moduleHandlers.length) return;
        [
            ["camera-changed", () => { void recoverChanged("camera"); }],
            ["microphone-changed", () => { void recoverChanged("microphone"); }],
            ["playback-device-changed", () => { void recoverChanged("playback"); }]
        ].forEach(([event, handler]) => { AgoraRTC.on(event, handler); moduleHandlers.push([event, handler]); });
    }

    async function preparePreCall() {
        if (!AgoraRTC || typeof AgoraRTC.checkSystemRequirements !== "function" || !AgoraRTC.checkSystemRequirements()
            || typeof AgoraRTC.getMicrophones !== "function" || typeof AgoraRTC.getCameras !== "function"
            || typeof AgoraRTC.createMicrophoneAudioTrack !== "function" || typeof AgoraRTC.createCameraVideoTrack !== "function") {
            onState({ state: "BLOCKED", code: "UNSUPPORTED" });
            return false;
        }
        try {
            await enumerate(false);
            consented = true;
            if (!selected.microphone) throw Object.assign(new Error("microphone missing"), { code: "DEVICE_NOT_FOUND" });
            closeTrack(preview.microphone);
            preview.microphone = await factory("microphone", selected.microphone);
            if (selected.camera) {
                try {
                    closeTrack(preview.camera);
                    preview.camera = await factory("camera", selected.camera);
                } catch (error) {
                    preview.camera = null;
                    onState({ kind: "camera", state: "AUDIO_ONLY", code: errorCode(error) });
                }
            }
            onState({ state: "READY", devices: snapshot() });
            return true;
        } catch (error) {
            closeTrack(preview.microphone); preview.microphone = null;
            onState({ kind: "microphone", state: "BLOCKED", code: errorCode(error) });
            return false;
        }
    }

    function claimPreview(kind) {
        const track = preview[kind];
        preview[kind] = null;
        return track;
    }
    function releasePreviews() { closeTrack(preview.microphone); closeTrack(preview.camera); preview.microphone = null; preview.camera = null; }
    function snapshot() {
        return { devices: { microphone: devices.microphone.slice(), camera: devices.camera.slice(), playback: devices.playback.slice() },
            selected: { ...selected }, microphoneReady: !!preview.microphone || !!getTrack("microphone") };
    }
    function dispose() {
        releasePreviews();
        if (AgoraRTC && typeof AgoraRTC.off === "function") moduleHandlers.forEach(([event, handler]) => AgoraRTC.off(event, handler));
        moduleHandlers.length = 0;
    }

    bindModuleEvents();
    return { preparePreCall, claimPreview, releasePreviews, switchLocal, switchPlayback, snapshot, dispose, createTrack: factory };
}

function createVideoCallRuntime(overrides = {}) {
    const document = overrides.document || globalThis.document;
    const window = overrides.window || globalThis.window;
    const fetch = overrides.fetch || globalThis.fetch;
    const AgoraRTC = overrides.AgoraRTC || globalThis.AgoraRTC;
    const SockJS = overrides.SockJS || globalThis.SockJS;
    const Stomp = overrides.Stomp || globalThis.Stomp;
    const AgoraReliability = overrides.AgoraReliability || globalThis.AgoraReliability;
    const VideoCallFlow = overrides.VideoCallFlow || globalThis.VideoCallFlow;

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
let client = AgoraRTC.createClient({ mode: "rtc", codec: "vp8" });
let adaptiveMedia = null;
const reliability = new AgoraReliability.AgoraReliabilityController({
    client,
    appId: AGORA_APP_ID,
    createClient: () => AgoraRTC.createClient({ mode: "rtc", codec: "vp8" }),
    onClientReplaced: replacement => { client = replacement; if (adaptiveMedia) adaptiveMedia.bindClient(replacement); },
    fetchToken: async sessionId => {
        const response = await fetch(`/api/sessions/${sessionId}/token`, { credentials: "include" });
        return { status: response.status, ok: response.ok, data: response.ok ? await response.json() : null };
    },
    isAuthorized: session => !!session && session.id === currentSessionId,
    isExactObjectCurrent: (type, scope, object) => {
        if (["JOIN", "TRANSPORT_RECOVERY", "TOKEN_RENEW", "PUBLISH", "SUBSCRIBE"].includes(type)) return object === client;
        if (type === "DEVICE_SWITCH" && scope.startsWith("device-replace:")) return object === client;
        if (type === "DEVICE_SWITCH" && scope.includes("camera")) return object === localVideoTrack;
        if (type === "DEVICE_SWITCH" && scope.includes("microphone")) return object === localAudioTrack;
        return true;
    },
    getRetainedTracks: () => [localAudioTrack, localVideoTrack].filter(Boolean),
    postRejoinSetup: () => { if (adaptiveMedia) void adaptiveMedia.enableVolume(); },
    onState: state => {
        if (roomInfo) roomInfo.dataset.rtcState = state.rtcConnectionState;
        if (adaptiveMedia) adaptiveMedia.onRtcState(state.rtcConnectionState, state.mediaReconnectState);
    },
    onDiagnostic: () => {}
});
reliability.bindClient(client);
let localAudioTrack = null;
let localVideoTrack = null;
let isAudioMuted = false;
let isVideoMuted = false;
let mediaJoined = false;
const remoteAudioTracks = new Set();

adaptiveMedia = new AgoraReliability.AdaptiveMediaController({
    client,
    getCurrentUid: () => currentPeerId,
    onState: state => {
        if (!roomInfo) return;
        roomInfo.dataset.fallbackState = state.fallbackState;
        roomInfo.dataset.networkHealth = state.networkHealth;
    },
    onNotice: code => { if (deviceStatus) deviceStatus.innerText = code === "ADAPTATION_UNAVAILABLE" ? "Adaptive video is unavailable; the call continues." : "Adaptive video is not supported by this browser."; },
    onVolume: active => {
        if (!roomInfo) return;
        const local = active.some(entry => String(entry.uid) === String(CURRENT_USER_ID));
        const remote = active.find(entry => String(entry.uid) !== String(CURRENT_USER_ID));
        roomInfo.dataset.microphoneActive = local ? "true" : "false";
        roomInfo.dataset.activeSpeaker = remote ? String(remote.uid) : "";
    }
});

// --- CẤU HÌNH WEBSOCKET (STOMP) ---
let stompClient = overrides.stompClient || null;
let currentSessionId = null;
let currentChannelName = null;
let currentPeerId = null;
let currentTagKey = null;
let recoveryInProgress = false;
let recoveryJoinStarted = false;

function activateReliabilitySession(sessionId, channelName, options = {}) {
    currentSessionId = sessionId;
    currentChannelName = channelName;
    recoveryInProgress = !!options.recovery;
    reliability.activate({ id: sessionId, channelName, terminal: false });
    adaptiveMedia.startSession(sessionId);
    adaptiveMedia.bindClient(client);
}

// --- GIAO DIỆN (UI PANELS) ---
const setupPanel = document.getElementById("setup-panel");
const waitingPanel = document.getElementById("waiting-panel");
const callPanel = document.getElementById("call-panel");
const videoContainer = document.getElementById("video-container");
const roomInfo = document.getElementById("room-info");
const topicTagSelect = document.getElementById("tag-select-topic");
const levelTagSelect = document.getElementById("tag-select-level");
const activityTagSelect = document.getElementById("tag-select-activity");
const precallPanel = document.getElementById("precall-panel");
const deviceStatus = document.getElementById("device-status");
const microphoneSelect = document.getElementById("microphone-select");
const cameraSelect = document.getElementById("camera-select");
const playbackSelect = document.getElementById("playback-select");
const playbackLabel = document.getElementById("playback-label");
const joinMediaButton = document.getElementById("join-media-btn");
const enableAudioButton = document.getElementById("enable-audio-btn");

function deviceMessage(code, state) {
    if (state === "READY") return "Thiết bị đã sẵn sàng. Bạn có thể vào cuộc gọi.";
    if (state === "AUDIO_ONLY") return "Camera không khả dụng. Cuộc gọi sẽ chỉ dùng âm thanh.";
    if (state === "DEFAULT_OUTPUT") return "Loa đã chuyển về đầu ra mặc định của trình duyệt.";
    if (code === "PERMISSION_DENIED") return "Quyền truy cập thiết bị bị từ chối.";
    if (code === "DEVICE_MISSING") return "Không tìm thấy thiết bị đã chọn.";
    if (code === "DEVICE_BUSY") return "Thiết bị đang được ứng dụng khác sử dụng.";
    if (code === "UNSUPPORTED") return "Trình duyệt không hỗ trợ thiết bị cần thiết.";
    return "Không thể chuẩn bị thiết bị. Hãy kiểm tra lại rồi thử lại.";
}

function renderDevices(snapshot) {
    const populate = (select, entries, selectedId) => {
        if (!select) return;
        select.innerHTML = "";
        entries.forEach((entry, index) => {
            if (typeof document.createElement === "function") {
                const option = document.createElement("option");
                option.value = entry.deviceId;
                option.innerText = entry.label || `Thiết bị ${index + 1}`;
                option.selected = entry.deviceId === selectedId;
                select.appendChild(option);
            }
        });
        select.disabled = entries.length === 0;
        if (selectedId) select.value = selectedId;
    };
    populate(microphoneSelect, snapshot.devices.microphone, snapshot.selected.microphone);
    populate(cameraSelect, snapshot.devices.camera, snapshot.selected.camera);
    populate(playbackSelect, snapshot.devices.playback, snapshot.selected.playback);
    const playbackSupported = snapshot.devices.playback.length > 0;
    if (playbackSelect) playbackSelect.style.display = playbackSupported ? "" : "none";
    if (playbackLabel) playbackLabel.style.display = playbackSupported ? "" : "none";
}

const deviceControls = createDeviceControls({
    AgoraRTC,
    reliability,
    getClient: () => client,
    getTrack: kind => kind === "microphone" ? localAudioTrack : localVideoTrack,
    replaceTrack: (kind, replacement) => {
        if (kind === "microphone") localAudioTrack = replacement;
        else localVideoTrack = replacement;
        if (kind === "camera" && replacement && typeof replacement.play === "function") replacement.play("local-player");
    },
    getRemoteAudioTracks: () => remoteAudioTracks,
    onState: state => {
        if (state.devices) renderDevices(state.devices);
        if (deviceStatus) deviceStatus.innerText = deviceMessage(state.code, state.state);
        if (joinMediaButton) joinMediaButton.disabled = !deviceControls.snapshot().microphoneReady;
        if (state.kind === "microphone" && state.state === "BLOCKED") document.getElementById("mic-btn").disabled = true;
        if (state.kind === "camera" && state.state === "AUDIO_ONLY") document.getElementById("cam-btn").disabled = true;
    }
});

function showPreCallPanel() {
    waitingPanel.style.display = "none";
    precallPanel.style.display = "block";
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    if (deviceStatus) deviceStatus.innerText = "Kiểm tra thiết bị trước khi vào cuộc gọi.";
    if (joinMediaButton) joinMediaButton.disabled = true;
}

async function checkDevicesForPreCall() {
    const ready = await deviceControls.preparePreCall();
    const snapshot = deviceControls.snapshot();
    renderDevices(snapshot);
    if (joinMediaButton) joinMediaButton.disabled = !ready || !snapshot.microphoneReady;
}

async function joinFromPreCall() {
    if (!deviceControls.snapshot().microphoneReady || !currentSessionId) return;
    precallPanel.style.display = "none";
    callPanel.style.display = "block";
    videoContainer.style.display = "flex";
    await joinAgoraCall(currentChannelName, currentSessionId);
}

async function retryBlockedAudio() {
    if (typeof AgoraRTC.resumeAudioContext === "function") AgoraRTC.resumeAudioContext();
    let audioPlayed = false;
    for (let attempt = 0; attempt < 2 && !audioPlayed; attempt++) {
        for (const track of remoteAudioTracks) {
            try { await Promise.resolve(track.play()); audioPlayed = true; } catch (_) {}
        }
        if (!audioPlayed && attempt === 0) await new Promise(resolve => setTimeout(resolve, 250));
    }
    if (audioPlayed && enableAudioButton) enableAudioButton.style.display = "none";
}

function markAutoplayBlocked(kind) {
    if (kind === "audio" && enableAudioButton) {
        enableAudioButton.style.display = "";
        if (typeof enableAudioButton.focus === "function") enableAudioButton.focus();
    }
    if (deviceStatus) deviceStatus.innerText = kind === "audio"
        ? "Âm thanh từ đối tác đang bị chặn. Chọn Bật âm thanh để tiếp tục."
        : "Video từ đối tác chưa thể phát. Bạn vẫn có thể tiếp tục cuộc gọi âm thanh.";
}

// --- GẮN SỰ KIỆN NÚT BẤM ---
document.getElementById("find-partner-btn").addEventListener("click", startSearch);
document.getElementById("cancel-search-btn").addEventListener("click", cancelSearch);
document.getElementById("end-call-btn").addEventListener("click", endCall);
document.getElementById("mic-btn").addEventListener("click", toggleMic);
document.getElementById("cam-btn").addEventListener("click", toggleCam);
document.getElementById("check-devices-btn").addEventListener("click", checkDevicesForPreCall);
document.getElementById("join-media-btn").addEventListener("click", joinFromPreCall);
document.getElementById("cancel-precall-btn").addEventListener("click", endCall);
if (microphoneSelect) microphoneSelect.addEventListener("change", () => { void deviceControls.switchLocal("microphone", microphoneSelect.value); });
if (cameraSelect) cameraSelect.addEventListener("change", () => { void deviceControls.switchLocal("camera", cameraSelect.value); });
if (playbackSelect) playbackSelect.addEventListener("change", () => { void deviceControls.switchPlayback(playbackSelect.value); });
if (enableAudioButton) enableAudioButton.addEventListener("click", retryBlockedAudio);
if (AgoraRTC && typeof AgoraRTC.on === "function") {
    AgoraRTC.on("autoplay-failed", info => markAutoplayBlocked(info && info.type === "video" ? "video" : "audio"));
    AgoraRTC.on("audio-context-state-changed", state => { if (state === "interrupted") markAutoplayBlocked("audio"); });
}
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
        activateReliabilitySession(session.id, session.channelName);
        currentPeerId = session.currentUserId === session.user1Id ? session.user2Id : session.user1Id;
        connectWebSocket(function () {
            waitingPanel.querySelector("p").innerText = "RECONNECTING: joining the previous call...";
            stompClient.send('/app/recover-session', {}, JSON.stringify({}));
        });
    } catch (error) {
        showSetupPanel();
        reliability.diagnostic(null, error, false);
        alert("Unable to restore the active call. Please retry.");
    }
}

function showSetupPanel() {
    recoveryInProgress = false;
    waitingPanel.style.display = "none";
    callPanel.style.display = "none";
    videoContainer.style.display = "none";
    precallPanel.style.display = "none";
    setupPanel.style.display = "block";
}

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

    connectWebSocket(sendJoinRequest);
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
        stompClient.send("/app/cancel-search", {}, JSON.stringify({}));
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
    if (currentSessionId && mediaJoined) {
        fetch(`/api/sessions/${currentSessionId}/leave-agora`, {
            method: 'POST',
            headers: getCsrfHeaders(),
            credentials: 'include'
        }).catch(() => {});
    }
    deviceControls.releasePreviews();
    leaveAgoraCall();
}

// Xử lý gói tin trả về từ Server
function handleMatchMessage(message) {
    if (message.status === "WAITING") {
    } 
    else if (message.status === "MATCHED") {

        // Lưu session info
        currentSessionId = message.sessionId;
        currentChannelName = message.channelName;
        activateReliabilitySession(message.sessionId, message.channelName);
        currentPeerId = message.peerId;
        
        // Chuyển giao diện sang Màn hình Gọi
        waitingPanel.style.display = "none";
        callPanel.style.display = "block";
        videoContainer.style.display = "flex"; 
        
        roomInfo.innerText = "Phòng: " + message.channelName + " | Đối tác: " + message.peerUserName + " (ID: " + message.peerId + ")";
        
        // Kích hoạt camera và join Agora
        showPreCallPanel();
    } 
    else if (message.status === "RECOVERY_READY") {
        if (recoveryJoinStarted) {
            return;
        }
        recoveryJoinStarted = true;
        currentSessionId = message.sessionId;
        currentChannelName = message.channelName;
        activateReliabilitySession(message.sessionId, message.channelName);
        currentPeerId = message.peerId;
        waitingPanel.style.display = "none";
        callPanel.style.display = "block";
        videoContainer.style.display = "flex";
        roomInfo.innerText = "Room: " + message.channelName + " | Reconnecting to peer";
        showPreCallPanel();
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
        if (message.status === "SESSION_ENDED") {
            reliability.markTerminal();
            void leaveAgoraCall("TERMINAL");
        }
        else reliability.invalidate("SESSION_REPLACED", { replaceSession: true });
        alert("The previous call has ended. You can start matchmaking again.");
        showSetupPanel();
    }
    else if (message.status === "PEER_DISCONNECTED") {
        alert("Đối tác đã rời phòng!");
        leaveAgoraCall();
    }
}

// ==========================================
// 2. LOGIC GỌI VIDEO (AGORA SDK)
// ==========================================

async function createOwnedLocalTracks(flow) {
    const bundle = { tracks: [], audioTrack: null, videoTrack: null };
    bundle.audioTrack = await reliability.ordinary("CREATE_MIC_TRACK", "mic",
        () => AgoraRTC.createMicrophoneAudioTrack(), () => {}, false);
    if (!reliability.isCurrentCallFlow(flow)) return bundle;
    if (bundle.audioTrack) bundle.tracks.push(bundle.audioTrack);
    bundle.videoTrack = await reliability.ordinary("CREATE_CAMERA_TRACK", "camera",
        () => AgoraRTC.createCameraVideoTrack(), () => {}, false);
    if (!reliability.isCurrentCallFlow(flow)) return bundle;
    if (bundle.videoTrack) bundle.tracks.push(bundle.videoTrack);
    return bundle;
}

async function cleanupOwnedBundle(flow, bundle) {
    for (const track of (bundle && bundle.tracks) || []) {
        if (track === localAudioTrack) localAudioTrack = null;
        if (track === localVideoTrack) localVideoTrack = null;
        const owner = { operationId: track.__ownerOperationId };
        reliability.cleanupResource(owner, track);
    }
}

const callFlow = new VideoCallFlow.CallFlowOrchestrator({
    reliability,
    fetchInitialToken: () => reliability.fetchInitialToken(),
    join: async (flow, tokenData) => {
        const joined = await reliability.shared("JOIN", "transport", flow.clientRef,
            exact => exact.join(AGORA_APP_ID, tokenData.channelName, tokenData.token, tokenData.uid),
            async (exact, op, accepted) => {
                if (accepted && reliability.isCurrentCallFlow(flow) && exact.connectionState === "CONNECTED") return true;
                if (exact.connectionState !== "DISCONNECTED") await exact.leave();
                return exact.connectionState === "DISCONNECTED";
            }, () => {}, false);
        if (joined === null) throw Object.assign(new Error(), { code: "NETWORK_ERROR" });
    },
    createMicrophone: async flow => {
        const previewTrack = deviceControls.claimPreview("microphone");
        const track = previewTrack || await reliability.ordinary("CREATE_MIC_TRACK", "mic",
            () => deviceControls.createTrack("microphone", deviceControls.snapshot().selected.microphone), () => {}, false);
        if (!track) throw Object.assign(new Error(), { code: "DEVICE_NOT_FOUND", mediaKind: "microphone" });
        track.__callFlowId = flow.flowId;
        return track;
    },
    createCamera: async flow => {
        const previewTrack = deviceControls.claimPreview("camera");
        const track = previewTrack || await reliability.ordinary("CREATE_CAMERA_TRACK", "camera",
            () => deviceControls.createTrack("camera", deviceControls.snapshot().selected.camera), () => {}, false);
        if (!track) throw Object.assign(new Error(), { code: "DEVICE_NOT_FOUND", mediaKind: "camera" });
        track.__callFlowId = flow.flowId;
        return track;
    },
    onOptionalCameraFailure: (flow, error) => {
        reliability.diagnostic(null, error, false);
        reliability.setRtcState("CONNECTED");
        const cameraButton = document.getElementById("cam-btn");
        if (cameraButton) cameraButton.disabled = true;
    },
    markCameraDegraded: flow => {
        if (!reliability.isCurrentCallFlow(flow)) return;
        if (roomInfo) {
            roomInfo.dataset.mediaState = "AUDIO_ONLY_CAMERA_UNAVAILABLE";
            roomInfo.innerText = "Room: " + currentChannelName + " | Audio-only (camera unavailable)";
        }
    },
    adoptTracks: (flow, bundle) => {
        if (!reliability.isCurrentCallFlow(flow)) return;
        localAudioTrack = bundle.audioTrack;
        localVideoTrack = bundle.videoTrack;
        document.getElementById("mic-btn").disabled = !localAudioTrack;
        document.getElementById("cam-btn").disabled = !localVideoTrack;
        if (localVideoTrack) localVideoTrack.play("local-player");
    },
    publish: async (flow, tracks) => {
        if (!tracks.length) return;
        const published = await reliability.shared("PUBLISH", "local-publish", flow.clientRef,
            exact => exact.publish(tracks),
            async (exact, op, accepted) => {
                if (accepted && reliability.isCurrentCallFlow(flow)) return true;
                try { await exact.unpublish(tracks); return true; }
                catch (error) { reliability.diagnostic(op, error, true); return false; }
            }, () => {}, false);
        if (published === null) throw Object.assign(new Error(), { code: "NETWORK_ERROR" });
        if (tracks.includes(localVideoTrack)) await adaptiveMedia.afterLocalVideoPublished();
    },
    confirmJoin: (flow, sessionId) => reliability.confirmJoin(() => fetch(`/api/sessions/${sessionId}/join-agora`, {
        method: "POST", headers: getCsrfHeaders(), credentials: "include"
    })),
    isRecovery: () => recoveryInProgress && stompClient && stompClient.connected,
    publishRecoveryComplete: (flow, sessionId) => reliability.publishRecoveryComplete(() =>
        stompClient.send("/app/recovery-complete", {}, JSON.stringify({})), sessionId),
    markRecoveryComplete: flow => { if (reliability.isCurrentCallFlow(flow)) recoveryInProgress = false; },
    publishRecoveryFailure: (flow, sessionId) => reliability.tryClaimRecoverySideEffect(flow, "RECOVERY_FAILED", () =>
        stompClient && stompClient.connected && stompClient.send("/app/recovery-failed", {}, JSON.stringify({}))),
    cleanupOwnedBundle,
    cleanupCurrentFlow: async (flow, bundle, error) => {
        if (!reliability.isCurrentCallFlow(flow)) return;
        reliability.diagnostic(null, error, false);
        await cleanupOwnedBundle(flow, bundle);
        if (!reliability.isCurrentCallFlow(flow)) return;
        recoveryInProgress = false;
        recoveryJoinStarted = false;
        await leaveAgoraCall("MEDIA_FAILURE");
        reliability.setRtcState("FAILED");
    }
});

async function joinAgoraCall(channelName, sessionId) {
    const result = await callFlow.run(channelName, sessionId);
    mediaJoined = result.status === "CONNECTED_AV" || result.status === "CONNECTED_AUDIO_ONLY";
    if (mediaJoined) await adaptiveMedia.enableVolume();
    return result;
}

async function leaveAgoraCall(reason = "LEAVE") {
    const ownedTracks = [localAudioTrack, localVideoTrack].filter(Boolean);
    localAudioTrack = null;
    localVideoTrack = null;
    mediaJoined = false;
    adaptiveMedia.reset();
    adaptiveMedia.unbindClient();
    deviceControls.releasePreviews();
    document.getElementById("mic-btn").disabled = false;
    document.getElementById("cam-btn").disabled = false;

    await reliability.cleanupCall(ownedTracks, reason);
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
reliability.registerClientHandler("user-published", async (user, mediaType) => {
    const ownedSessionId = currentSessionId;
    const subscribed = await reliability.shared("SUBSCRIBE", `remote:${ownedSessionId}:${user.uid}:${mediaType}`, client,
        exact => exact.subscribe(user, mediaType),
        async (exact, op, accepted) => {
            if (accepted && reliability.isCurrentOwnerForCommit(op)) return true;
            try { await exact.unsubscribe(user, mediaType); return true; }
            catch (error) { reliability.diagnostic(op, error, true); return false; }
        }, () => {}, false);
    if (subscribed === null || ownedSessionId !== currentSessionId) return;
    if (mediaType === "video") {
        await adaptiveMedia.configureRemote(user.uid);
        try { await Promise.resolve(user.videoTrack.play("remote-player")); }
        catch (_) { markAutoplayBlocked("video"); }
    }
    if (mediaType === "audio") {
        remoteAudioTracks.add(user.audioTrack);
        try { await Promise.resolve(user.audioTrack.play()); }
        catch (_) { markAutoplayBlocked("audio"); }
    }
});

// Lắng nghe sự kiện đối tác (Remote) tắt camera
reliability.registerClientHandler("user-unpublished", (user, mediaType) => {
    if (mediaType === "video") document.getElementById("remote-player").innerHTML = "";
    if (mediaType === "audio") remoteAudioTracks.delete(user.audioTrack);
});

window.addEventListener("pagehide", () => { deviceControls.dispose(); return reliability.cleanupCall([localAudioTrack, localVideoTrack].filter(Boolean), "DISPOSE"); });

    return { joinAgoraCall, leaveAgoraCall, activateReliabilitySession,
        getReliability: () => reliability,
        getDeviceControls: () => deviceControls,
        retryBlockedAudio,
        getTracks: () => ({ audio: localAudioTrack, video: localVideoTrack }) };
}

if (typeof module === "object" && module.exports) module.exports = { createVideoCallRuntime };
else createVideoCallRuntime();
