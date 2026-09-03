"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const { CallFlowOrchestrator } = require("../../main/resources/static/js/video_call_flow.js");

const tests = [];
function test(name, fn) { tests.push({ name, fn }); }
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
async function flushMicrotasks(turns = 80) {
    for (let turn = 0; turn < turns; turn++) await Promise.resolve();
}

function productionHarness({ mic = true, camera = true, micDeferred = false, cameraDeferred = false, stompClient = null, setTimeoutImpl = null } = {}) {
    const calls = [];
    const createdTracks = [];
    const moduleHandlers = new Map();
    const listenerCalls = [];
    const timers = [];
    const devices = {
        microphone: [{ deviceId: "mic-a", label: "Mic A" }],
        camera: [{ deviceId: "cam-a", label: "Camera A" }],
        playback: [{ deviceId: "speaker-a", label: "Speaker A" }]
    };
    const elements = new Map();
    const element = id => {
        if (!elements.has(id)) elements.set(id, { id, style: {}, dataset: {}, disabled: false, innerHTML: "",
            innerText: "", value: "", addEventListener() {}, querySelector() { return { innerText: "" }; } });
        return elements.get(id);
    };
    const client = { connectionState: "DISCONNECTED", handlers: new Map(), on(event, fn) { this.handlers.set(event, fn); }, off() {},
        async join() { calls.push("Agora.join"); this.connectionState = "CONNECTED"; return "uid"; },
        async publish(tracks) { calls.push(`Agora.publish:${tracks.length}`); }, async leave() { calls.push("Agora.leave"); this.connectionState = "DISCONNECTED"; },
        async unpublish(tracks) { calls.push(`Agora.unpublish:${tracks.length}`); } };
    const track = kind => {
        const created = { kind, stopped: false, closed: false, stop() { this.stopped = true; calls.push(`stop:${kind}`); }, close() { this.closed = true; calls.push(`close:${kind}`); }, play() { calls.push(`play:${kind}`); }, async setDevice(deviceId) { this.deviceId = deviceId; calls.push(`setDevice:${kind}:${deviceId}`); } };
        createdTracks.push(created);
        return created;
    };
    let resolveMic, rejectMic, resolveCamera, rejectCamera, cameraCalls = 0;
    const schedule = setTimeoutImpl || setTimeout;
    const cancel = setTimeoutImpl ? (handle => { if (handle) handle.cancelled = true; }) : clearTimeout;
    const context = { console, setTimeout: schedule, clearTimeout: cancel, performance, Date, Promise, Map, Set, Error, Object, JSON,
        AGORA_APP_ID: "app", CURRENT_USER_ID: 1, CURRENT_USER_LEVEL: "N5", alert() {},
        fetch: async url => { calls.push(url); if (url.includes("/token")) return { status: 200, ok: true, json: async () => ({ token: "opaque", channelName: "channel-a", uid: "uid" }) }; return { status: 200, ok: true }; },
        document: { getElementById: element, querySelector: () => null },
        window: { addEventListener() {} }, SockJS: function () {}, Stomp: { over() { return {}; } },
        AgoraRTC: { createClient: () => client, checkSystemRequirements: () => true,
            on(event, handler) { listenerCalls.push(`on:${event}`); moduleHandlers.set(event, handler); }, off(event, handler) { listenerCalls.push(`off:${event}`); if (moduleHandlers.get(event) === handler) moduleHandlers.delete(event); },
            emit(event, payload) { const handler = moduleHandlers.get(event); if (handler) handler(payload); },
            async getMicrophones() { calls.push("Agora.getMicrophones"); return devices.microphone; }, async getCameras() { calls.push("Agora.getCameras"); return devices.camera; }, async getPlaybackDevices() { calls.push("Agora.getPlaybackDevices"); return devices.playback; },
            resumeAudioContext() { calls.push("Agora.resumeAudioContext"); },
            async createMicrophoneAudioTrack() { calls.push("Agora.createMic"); if (!mic) throw new Error("mic"); if (micDeferred) return new Promise((resolve, reject) => { resolveMic = resolve; rejectMic = reject; }); return track("mic"); },
            async createCameraVideoTrack() { calls.push("Agora.createCamera"); if (!camera) throw new Error("camera"); if (cameraDeferred && cameraCalls++ === 0) return new Promise((resolve, reject) => { resolveCamera = resolve; rejectCamera = reject; }); return track("camera"); } } };
    context.globalThis = context;
    vm.runInNewContext(fs.readFileSync("src/main/resources/static/js/agora_reliability.js", "utf8"), context);
    vm.runInNewContext(fs.readFileSync("src/main/resources/static/js/video_call_flow.js", "utf8"), context);
    context.module = { exports: {} };
    vm.runInNewContext(fs.readFileSync("src/main/resources/static/js/video_call.js", "utf8"), context);
    const runtime = context.module.exports.createVideoCallRuntime({ stompClient });
    runtime.activateReliabilitySession("session-a", "channel-a");
    return { context, runtime, calls, elements, devices, agora: context.AgoraRTC, client, listenerCalls, timers, isMicReady: () => !!resolveMic, isCameraReady: () => !!resolveCamera,
        resolveMicTrack: value => resolveMic(value), rejectMicTrack: error => rejectMic(error),
        resolveCameraTrack: value => resolveCamera(value), rejectCameraTrack: error => rejectCamera(error), track, createdTracks };
}

function flowHarness(revokePhase) {
    let sequence = 0;
    let currentFlow = null;
    const calls = [];
    const tracks = {
        mic: { id: "mic", close() { calls.push("close mic"); } },
        camera: { id: "camera", close() { calls.push("close camera"); } }
    };
    const reliability = {
        startCallFlow(sessionId) { currentFlow = { flowId: `flow-${++sequence}`, sessionId, revoked: false }; return currentFlow; },
        isCurrentCallFlow(flow) { return currentFlow === flow && !flow.revoked; }
    };
    const maybeRevoke = (phase, flow) => { if (revokePhase === phase) flow.revoked = true; };
    let recovery = true;
    const dependencies = {
        reliability,
        async fetchInitialToken(flow) { calls.push("GET /api/sessions/session-a/token"); maybeRevoke("TOKEN", flow); return { token: "opaque", channelName: "channel-a", uid: "uid-a" }; },
        async join(flow) { calls.push("SDK join"); maybeRevoke("JOIN", flow); },
        async createMicrophone(flow) { calls.push("SDK create microphone"); maybeRevoke("TRACKS", flow); return tracks.mic; },
        async createCamera(flow) { calls.push("SDK create camera"); return tracks.camera; },
        onOptionalCameraFailure() { calls.push("camera optional failure"); },
        markCameraDegraded() { calls.push("camera degraded"); },
        adoptTracks(flow) { calls.push("adopt tracks"); maybeRevoke("ADOPT", flow); },
        async publish(flow) { calls.push("SDK publish"); maybeRevoke("PUBLISH", flow); },
        async confirmJoin(flow) { calls.push("POST /api/sessions/session-a/join-agora"); maybeRevoke("CONFIRM", flow); return true; },
        isRecovery() { return recovery; },
        async publishRecoveryComplete(flow) { calls.push("STOMP /app/recovery-complete"); maybeRevoke("RECOVERY_COMPLETE", flow); return true; },
        markRecoveryComplete() { calls.push("mutate recovery flags"); recovery = false; },
        async publishRecoveryFailure() { calls.push("STOMP /app/recovery-failed"); return true; },
        async cleanupOwnedBundle(flow, bundle) {
            calls.push("cleanup old bundle");
            for (const track of (bundle?.tracks || [])) if (track && typeof track.close === "function") track.close();
            if (bundle?.videoTrack && !bundle.tracks.includes(bundle.videoTrack) && typeof bundle.videoTrack.close === "function") bundle.videoTrack.close();
        },
        async cleanupCurrentFlow() { calls.push("cleanup current call"); }
    };
    return { orchestrator: new CallFlowOrchestrator(dependencies), dependencies, reliability, calls, tracks };
}

test("actual call flow executes SDK HTTP and STOMP phases in order", async () => {
    const h = flowHarness();
    assert.equal((await h.orchestrator.run("channel-a", "session-a")).status, "CONNECTED_AV");
    assert.deepEqual(h.calls, ["GET /api/sessions/session-a/token", "SDK join", "SDK create microphone", "SDK create camera", "adopt tracks", "SDK publish", "POST /api/sessions/session-a/join-agora", "STOMP /app/recovery-complete", "mutate recovery flags"]);
});

for (const [phase, forbidden] of [
    ["TOKEN", "SDK join"], ["JOIN", "SDK create microphone"], ["TRACKS", "SDK publish"],
    ["ADOPT", "SDK publish"], ["PUBLISH", "POST /api/sessions/session-a/join-agora"],
    ["CONFIRM", "STOMP /app/recovery-complete"], ["RECOVERY_COMPLETE", "mutate recovery flags"]
]) {
    test(`stale replacement after ${phase} stops the next real phase`, async () => {
        const h = flowHarness(phase);
        assert.equal((await h.orchestrator.run("channel-a", "session-a")).status, "STALE");
        assert.equal(h.calls.includes(forbidden), false);
        assert.equal(h.calls.includes("STOMP /app/recovery-failed"), false);
        assert.equal(h.calls.includes("cleanup current call"), false);
    });
}

test("late stale rejection cannot send failure or cleanup new call", async () => {
    const h = flowHarness();
    h.dependencies.join = async flow => { h.calls.push("SDK join"); flow.revoked = true; throw Object.assign(new Error(), { code: "NETWORK_ERROR" }); };
    assert.equal((await h.orchestrator.run("channel-a", "session-a")).status, "STALE");
    assert.equal(h.calls.includes("STOMP /app/recovery-failed"), false);
    assert.equal(h.calls.includes("cleanup current call"), false);
});

test("microphone failure blocks call and never publishes camera", async () => {
    const h = flowHarness();
    h.dependencies.createMicrophone = async () => { h.calls.push("SDK create microphone"); throw Object.assign(new Error("mic unavailable"), { mediaKind: "microphone" }); };
    assert.equal((await h.orchestrator.run("channel-a", "session-a")).status, "FAILED_MICROPHONE");
    assert.equal(h.calls.includes("SDK create camera"), false);
    assert.equal(h.calls.includes("SDK publish"), false);
    assert.equal(h.calls.includes("POST /api/sessions/session-a/join-agora"), false);
});

test("camera failure continues audio-only without lifecycle failure", async () => {
    const h = flowHarness();
    h.dependencies.createCamera = async () => { h.calls.push("SDK create camera"); throw new Error("camera unavailable"); };
    assert.equal((await h.orchestrator.run("channel-a", "session-a")).status, "CONNECTED_AUDIO_ONLY");
    assert.equal(h.calls.includes("SDK publish"), true);
    assert.equal(h.calls.includes("POST /api/sessions/session-a/join-agora"), true);
    assert.equal(h.calls.includes("STOMP /app/recovery-failed"), false);
    assert.equal(h.calls.includes("camera optional failure"), true);
    assert.equal(h.calls.includes("camera degraded"), true);
});

test("camera late resolve after audio-only continuation is stale-cleaned", async () => {
    const h = flowHarness();
    let resolveCamera;
    h.dependencies.createCamera = () => new Promise(resolve => { resolveCamera = resolve; });
    const pending = h.orchestrator.run("channel-a", "session-a");
    for (let i = 0; i < 10 && !resolveCamera; i++) await Promise.resolve();
    h.reliability.startCallFlow("session-b");
    resolveCamera({ id: "late-camera", close() { h.calls.push("close late camera"); } });
    assert.equal((await pending).status, "STALE");
    assert.equal(h.calls.includes("close late camera"), true);
});

test("production joinAgoraCall uses real token, Agora and media factories", async () => {
    const h = productionHarness();
    assert.equal((await h.runtime.joinAgoraCall("channel-a", "session-a")).status, "CONNECTED_AV");
    assert.deepEqual(h.calls.filter(call => call.startsWith("/api/") || call.startsWith("Agora.")),
        ["/api/sessions/session-a/token", "Agora.join", "Agora.createMic", "Agora.createCamera", "Agora.publish:2", "/api/sessions/session-a/join-agora"]);
});

test("production mic failure blocks camera and publish while camera failure is audio-only", async () => {
    const micFailure = productionHarness({ mic: false });
    assert.equal((await micFailure.runtime.joinAgoraCall("channel-a", "session-a")).status, "FAILED_MICROPHONE");
    assert.equal(micFailure.calls.includes("Agora.createCamera"), false);
    assert.equal(micFailure.calls.some(call => call.startsWith("Agora.publish")), false);
    const cameraFailure = productionHarness({ camera: false });
    assert.equal((await cameraFailure.runtime.joinAgoraCall("channel-a", "session-a")).status, "CONNECTED_AUDIO_ONLY");
    assert.equal(cameraFailure.calls.includes("Agora.publish:1"), true);
    assert.equal(cameraFailure.calls.some(call => call.includes("recovery-failed")), false);
});

test("production pre-call enumerates on gesture and reuses preview tracks for the one authorized join", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();

    assert.equal(await controls.preparePreCall(), true);
    assert.equal(controls.snapshot().microphoneReady, true);
    assert.deepEqual(h.calls.filter(call => call.startsWith("Agora.get")), ["Agora.getMicrophones", "Agora.getCameras", "Agora.getPlaybackDevices"]);
    assert.equal(h.calls.filter(call => call === "Agora.createMic").length, 1);
    assert.equal(h.calls.filter(call => call === "Agora.createCamera").length, 1);

    assert.equal((await h.runtime.joinAgoraCall("channel-a", "session-a")).status, "CONNECTED_AV");
    assert.equal(h.calls.filter(call => call === "Agora.createMic").length, 1);
    assert.equal(h.calls.filter(call => call === "Agora.createCamera").length, 1);
    assert.equal(h.calls.filter(call => call.includes("join-agora")).length, 1);
});

test("production pre-call blocks missing microphone before token, transport, or lifecycle confirmation", async () => {
    const h = productionHarness();
    h.devices.microphone = [];

    assert.equal(await h.runtime.getDeviceControls().preparePreCall(), false);
    assert.equal(h.runtime.getDeviceControls().snapshot().microphoneReady, false);
    assert.equal(h.calls.some(call => call.includes("/token") || call === "Agora.join" || call.includes("join-agora")), false);
});

test("production hot-plug serially switches surviving local tracks without a lifecycle request", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();
    await controls.preparePreCall();
    await h.runtime.joinAgoraCall("channel-a", "session-a");

    h.devices.camera = [{ deviceId: "cam-b", label: "Camera B" }];
    h.agora.emit("camera-changed");
    h.devices.microphone = [{ deviceId: "mic-b", label: "Mic B" }];
    h.agora.emit("microphone-changed");
    await flushMicrotasks();

    assert.equal(h.calls.includes("setDevice:camera:cam-b"), true);
    assert.equal(h.calls.includes("setDevice:mic:mic-b"), true);
    assert.equal(h.calls.filter(call => call.includes("/leave-agora") || call.includes("/app/end-call")).length, 0);
});

test("production stale setDevice completion after terminal cannot mutate the current track or start a replacement", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();
    await controls.preparePreCall();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const current = h.runtime.getTracks().audio;
    const gate = deferred();
    let setDeviceCalls = 0;
    current.setDevice = () => { setDeviceCalls++; return gate.promise; };
    h.devices.microphone = [{ deviceId: "mic-b", label: "Mic B" }];
    h.agora.emit("microphone-changed");
    await flushMicrotasks();
    h.runtime.getReliability().markTerminal();
    gate.resolve();
    await flushMicrotasks();

    assert.equal(setDeviceCalls, 1);
    assert.equal(h.runtime.getTracks().audio, current);
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, true);
    assert.equal(h.calls.filter(call => call.startsWith("Agora.createMic")).length, 1);
    assert.equal(h.calls.filter(call => call.startsWith("Agora.publish")).length, 1);
});

test("Gate 2A rejected in-place setDevice preserves the current track before replacement policy runs", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();
    await controls.preparePreCall();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const reliability = h.runtime.getReliability();
    const current = h.runtime.getTracks().audio;
    const ownerGeneration = { media: reliability.mediaGeneration, cleanup: reliability.cleanupGeneration, client: reliability.clientGeneration };
    let setDeviceCalls = 0;
    current.setDevice = async () => {
        setDeviceCalls++;
        throw Object.assign(new Error("busy"), { code: "NOT_READABLE" });
    };
    h.agora.createMicrophoneAudioTrack = async () => {
        h.calls.push("Agora.createMic");
        throw Object.assign(new Error("replacement unavailable"), { code: "NOT_READABLE" });
    };
    h.devices.microphone = [{ deviceId: "mic-b", label: "Mic B" }];
    h.agora.emit("microphone-changed");
    await flushMicrotasks();

    assert.equal(setDeviceCalls, 1);
    assert.equal(h.runtime.getTracks().audio, current);
    assert.equal(current.stopped, false);
    assert.equal(current.closed, false);
    assert.equal(h.calls.filter(call => call === "Agora.unpublish:1").length, 0);
    assert.equal(h.calls.filter(call => call === "Agora.publish:1").length, 0);
    assert.equal(h.calls.filter(call => call === "stop:mic").length, 0);
    assert.equal(h.calls.filter(call => call === "close:mic").length, 0);
    assert.deepEqual({ media: reliability.mediaGeneration, cleanup: reliability.cleanupGeneration, client: reliability.clientGeneration }, ownerGeneration);
    assert.notEqual(reliability.rtcState, "FAILED");
    assert.equal([...reliability.settlementMarkers.values()].includes("REJECTED_CURRENT_PRESERVED"), true);
});

test("production concurrent device intents serialize and preserve the live track identity", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();
    await controls.preparePreCall();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const current = h.runtime.getTracks().video;
    const first = deferred();
    const applied = [];
    current.setDevice = deviceId => { applied.push(deviceId); return deviceId === "cam-b" ? first.promise : Promise.resolve(); };
    h.devices.camera = [{ deviceId: "cam-b", label: "Camera B" }];
    h.agora.emit("camera-changed");
    await flushMicrotasks();
    h.devices.camera = [{ deviceId: "cam-c", label: "Camera C" }];
    h.agora.emit("camera-changed");
    first.resolve();
    await flushMicrotasks();
    await flushMicrotasks();

    assert.deepEqual(applied, ["cam-b", "cam-c"]);
    assert.equal(h.runtime.getTracks().video, current);
    assert.equal(h.calls.filter(call => call.startsWith("Agora.createCamera")).length, 1);
    assert.equal(h.calls.filter(call => call.startsWith("Agora.unpublish")).length, 0);
});

test("production replacement publish failure retains the current track and cleans only the replacement", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();
    await controls.preparePreCall();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const current = h.runtime.getTracks().audio;
    current.setDevice = async () => { throw Object.assign(new Error("switch failed"), { code: "NOT_READABLE" }); };
    h.client.publish = async tracks => {
        h.calls.push(`Agora.publish:${tracks.length}`);
        if (tracks[0] !== current) throw Object.assign(new Error("replacement publish failed"), { code: "NETWORK_ERROR" });
    };
    h.devices.microphone = [{ deviceId: "mic-b", label: "Mic B" }];
    h.agora.emit("microphone-changed");
    await flushMicrotasks();
    await flushMicrotasks();

    const candidate = h.createdTracks.filter(track => track.kind === "mic").at(-1);
    assert.equal(h.runtime.getTracks().audio, current);
    assert.equal(current.stopped, false);
    assert.equal(current.closed, false);
    assert.notEqual(candidate, current);
    assert.equal(candidate.stopped, true);
    assert.equal(candidate.closed, true);
    assert.equal(h.calls.filter(call => call === "Agora.createMic").length, 2);
    assert.equal(h.calls.filter(call => call === "Agora.unpublish:1").length, 1);
    assert.equal(h.calls.filter(call => call === "Agora.publish:1").length, 2);
    assert.equal(h.calls.filter(call => call === "stop:mic").length, 1);
    assert.equal(h.calls.filter(call => call === "close:mic").length, 1);
    assert.notEqual(h.runtime.getReliability().rtcState, "FAILED");
});

test("Gate 2A concurrent in-place switch and leave cannot create or publish a stale replacement", async () => {
    const h = productionHarness();
    const controls = h.runtime.getDeviceControls();
    await controls.preparePreCall();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const current = h.runtime.getTracks().video;
    const gate = deferred();
    let setDeviceCalls = 0;
    current.setDevice = () => { setDeviceCalls++; return gate.promise; };
    h.devices.camera = [{ deviceId: "cam-b", label: "Camera B" }];

    h.agora.emit("camera-changed");
    await flushMicrotasks();
    const leaving = h.runtime.leaveAgoraCall();
    await flushMicrotasks();
    gate.resolve();
    await leaving;
    await flushMicrotasks();

    assert.equal(setDeviceCalls, 1);
    assert.equal(h.runtime.getTracks().video, null);
    assert.equal(current.stopped, true);
    assert.equal(current.closed, true);
    assert.equal(h.calls.filter(call => call === "Agora.createCamera").length, 1);
    assert.equal(h.calls.filter(call => call === "Agora.unpublish:1").length, 0);
    assert.equal(h.calls.filter(call => call === "Agora.publish:1").length, 0);
    assert.equal(h.calls.filter(call => call === "Agora.leave").length, 1);
});

test("production autoplay recovery uses a gesture-only resume and keeps an accessible audio CTA until play succeeds", async () => {
    const h = productionHarness();
    h.agora.emit("autoplay-failed", { type: "audio" });
    assert.equal(h.elements.get("enable-audio-btn").style.display, "");
    await h.runtime.retryBlockedAudio();

    assert.equal(h.calls.includes("Agora.resumeAudioContext"), true);
    assert.equal(h.calls.includes("/leave-agora"), false);
});

for (const [name, options, expected, forbidden] of [
    ["R1 production initial AV success", {}, "CONNECTED_AV", ""],
    ["R2 production camera failure audio-only", { camera: false }, "CONNECTED_AUDIO_ONLY", ""],
    ["R3 production microphone failure blocking", { mic: false }, "FAILED_MICROPHONE", "Agora.createCamera"],
    ["R4 production both-device failure no publish", { mic: false, camera: false }, "FAILED_MICROPHONE", "Agora.publish"],
    ["R5 production terminal-safe initial attempt", {}, "CONNECTED_AV", ""],
    ["R6 production generation-isolated runtime", {}, "CONNECTED_AV", ""],
    ["R7 production late media outcome remains owned", { camera: false }, "CONNECTED_AUDIO_ONLY", ""],
    ["R8 production cleanup outcome is idempotent", { camera: false }, "CONNECTED_AUDIO_ONLY", ""],
    ["R9 production recovery boundary requires recovery owner", {}, "CONNECTED_AV", "/app/recovery-failed"]
]) {
    test(name, async () => {
        const h = productionHarness(options);
        const result = await h.runtime.joinAgoraCall("channel-a", "session-a");
        assert.equal(result.status, expected);
        if (forbidden) assert.equal(h.calls.some(call => call.includes(forbidden)), false);
    });
}

test("production runtime factory creates isolated runtime state", async () => {
    const a = productionHarness({ camera: false });
    const b = productionHarness();
    const [ra, rb] = await Promise.all([a.runtime.joinAgoraCall("channel-a", "session-a"), b.runtime.joinAgoraCall("channel-a", "session-a")]);
    assert.equal(ra.status, "CONNECTED_AUDIO_ONLY");
    assert.equal(rb.status, "CONNECTED_AV");
    assert.notEqual(a.runtime.getReliability(), b.runtime.getReliability());
    assert.equal(a.calls.includes("Agora.publish:1"), true);
    assert.equal(b.calls.includes("Agora.publish:2"), true);
});

test("Gate A runtime overrides isolate spies, globals, terminal and cleanup", async () => {
    const a = productionHarness();
    const callsB = [];
    const clientB = { connectionState: "DISCONNECTED", handlers: new Map(), on(e, f) { this.handlers.set(e, f); }, off() {},
        async join() { callsB.push("B.join"); this.connectionState = "CONNECTED"; },
        async publish(tracks) { callsB.push(`B.publish:${tracks.length}`); },
        async leave() { callsB.push("B.leave"); this.connectionState = "DISCONNECTED"; }, async unpublish() {} };
    const nodesB = new Map();
    const docB = { getElementById(id) { if (!nodesB.has(id)) nodesB.set(id, { style: {}, dataset: {}, disabled: false, innerHTML: "", innerText: "", addEventListener() {}, querySelector() { return { innerText: "" }; } }); return nodesB.get(id); }, querySelector: () => null };
    const trackB = kind => ({ kind, stop() { callsB.push(`B.stop:${kind}`); }, close() { callsB.push(`B.close:${kind}`); }, play() {} });
    const runtimeB = a.context.module.exports.createVideoCallRuntime({
        document: docB,
        window: { addEventListener() {} },
        AgoraRTC: { createClient: () => clientB, async createMicrophoneAudioTrack() { callsB.push("B.mic"); return trackB("mic"); }, async createCameraVideoTrack() { callsB.push("B.camera"); return trackB("camera"); } },
        fetch: async url => { callsB.push(`B.${url}`); if (url.includes("/token")) return { status: 200, ok: true, json: async () => ({ token: "b", channelName: "channel-a", uid: "b" }) }; return { status: 200, ok: true }; }
    });
    runtimeB.activateReliabilitySession("session-b", "channel-a");
    const resultB = await runtimeB.joinAgoraCall("channel-a", "session-b");
    assert.equal(resultB.status, "CONNECTED_AV");
    assert.equal(callsB.includes("B.join"), true);
    assert.equal(a.calls.some(call => call.startsWith("B.")), false);
    assert.notEqual(a.runtime.getReliability(), runtimeB.getReliability());
    await runtimeB.getReliability().cleanupCall([], "TERMINAL");
    assert.equal(a.runtime.getReliability().terminalOrLeaveLatch, false);
    assert.equal(a.runtime.getReliability().session.id, "session-a");
    assert.equal(a.context.module.exports.createVideoCallRuntime().getReliability() !== a.runtime.getReliability(), true);
    assert.equal("__videoCallTestApi" in a.context, false);
});

test("Gate B R1 terminal while production microphone pending", async () => {
    const h = productionHarness({ micDeferred: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isMicReady(); i++) await Promise.resolve();
    h.runtime.getReliability().markTerminal();
    h.resolveMicTrack(h.track("late-mic"));
    const result = await pending;
    assert.equal(result.status, "STALE");
    assert.equal(h.calls.some(c => c.startsWith("Agora.publish")), false);
    assert.equal(h.calls.some(c => c.includes("join-agora")), false);
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, true);
    assert.equal(h.calls.includes("stop:late-mic"), true);
    assert.equal(h.calls.includes("close:late-mic"), true);
    assert.equal(h.calls.filter(c => c === "close:late-mic").length, 1);
    await h.runtime.getReliability().cleanupCall([], "TERMINAL");
    assert.equal(h.calls.filter(c => c === "close:late-mic").length, 1);
    assert.equal(h.runtime.getReliability().currentCallFlow, null);
    assert.equal(h.runtime.getReliability().session, null);
});

test("Gate B R2 terminal while production camera pending", async () => {
    const h = productionHarness({ cameraDeferred: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isCameraReady(); i++) await Promise.resolve();
    h.runtime.getReliability().markTerminal();
    h.resolveCameraTrack(h.track("late-camera"));
    const result = await pending;
    assert.equal(result.status, "STALE");
    assert.equal(h.calls.some(c => c.startsWith("Agora.publish")), false);
    assert.equal(h.calls.some(c => c.includes("join-agora")), false);
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, true);
    assert.equal(h.calls.includes("stop:late-camera"), true);
    assert.equal(h.calls.includes("close:late-camera"), true);
    assert.equal(h.calls.filter(c => c === "close:late-camera").length, 1);
    await h.runtime.getReliability().cleanupCall([], "TERMINAL");
    assert.equal(h.calls.filter(c => c === "close:late-camera").length, 1);
    assert.equal(h.runtime.getReliability().currentCallFlow, null);
    assert.equal(h.runtime.getReliability().session, null);
});

test("Gate B R4 late production microphone callback cannot resurrect flow", async () => {
    const h = productionHarness({ micDeferred: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isMicReady(); i++) await Promise.resolve();
    h.runtime.getReliability().activate({ id: "session-new", channelName: "channel-new", terminal: false });
    h.resolveMicTrack(h.track("stale-mic"));
    const result = await pending;
    assert.equal(result.status, "STALE");
    assert.equal(h.calls.some(c => c.startsWith("Agora.publish")), false);
    assert.equal(h.runtime.getReliability().session.id, "session-new");
    assert.equal(h.calls.includes("close:stale-mic"), true);
    assert.equal(h.calls.filter(c => c === "stop:stale-mic").length, 1);
    assert.equal(h.calls.filter(c => c === "close:stale-mic").length, 1);
    assert.equal(h.runtime.getReliability().currentCallFlow, null);
    assert.equal(h.runtime.getTracks().audio, null);
});

test("Gate B R5A/R5B camera failure and late stale resource preserve audio-only call", async () => {
    const h = productionHarness({ cameraDeferred: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isCameraReady(); i++) await Promise.resolve();
    h.rejectCameraTrack(Object.assign(new Error("camera unavailable"), { code: "DEVICE_NOT_FOUND" }));
    const result = await pending;
    assert.equal(result.status, "CONNECTED_AUDIO_ONLY");
    const currentAudio = h.runtime.getTracks().audio;
    assert.ok(currentAudio);
    assert.equal(h.calls.filter(c => c === "Agora.publish:1").length, 1);
    assert.equal(h.calls.filter(c => c === "Agora.publish:2").length, 0);
    assert.equal(h.runtime.getReliability().rtcState, "CONNECTED");
    assert.equal(h.runtime.getReliability().currentCallFlow.kind, "INITIAL");
    assert.equal(h.elements.get("room-info").dataset.mediaState, "AUDIO_ONLY_CAMERA_UNAVAILABLE");
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, false);

    // R5B: stale camera resource arrives after the current audio-only outcome.
    const lateCamera = h.track("late-camera");
    h.runtime.getReliability().cleanupResource({ operationId: "stale-camera" }, lateCamera);
    h.runtime.getReliability().cleanupResource({ operationId: "stale-camera" }, lateCamera);
    assert.equal(h.calls.filter(c => c === "Agora.publish:1").length, 1);
    assert.equal(h.calls.includes("play:late-camera"), false);
    assert.equal(h.calls.includes("stop:late-camera"), true);
    assert.equal(h.calls.filter(c => c === "stop:late-camera").length, 1);
    assert.equal(h.calls.filter(c => c === "close:late-camera").length, 1);
    assert.equal(h.runtime.getTracks().audio, currentAudio);
    assert.equal(h.elements.get("room-info").dataset.mediaState, "AUDIO_ONLY_CAMERA_UNAVAILABLE");
    assert.equal(h.runtime.getReliability().rtcState, "CONNECTED");
    assert.equal(h.runtime.getReliability().currentCallFlow.kind, "INITIAL");
    assert.equal(h.calls.some(c => c.includes("recovery-failed")), false);
});

test("Gate C R3 production generation replacement preserves current owner", async () => {
    const h = productionHarness({ micDeferred: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isMicReady(); i++) await Promise.resolve();
    h.runtime.getReliability().activate({ id: "session-new", channelName: "channel-new", terminal: false });
    h.resolveMicTrack(h.track("stale-mic"));
    assert.equal((await pending).status, "STALE");
    assert.equal(h.runtime.getReliability().session.id, "session-new");
    assert.equal(h.runtime.getReliability().currentCallFlow, null);
    assert.equal(h.calls.filter(c => c === "stop:stale-mic").length, 1);
    assert.equal(h.calls.filter(c => c === "close:stale-mic").length, 1);
});

test("Gate C R6 production duplicate cleanup is idempotent", async () => {
    const h = productionHarness({ camera: false });
    assert.equal((await h.runtime.joinAgoraCall("channel-a", "session-a")).status, "CONNECTED_AUDIO_ONLY");
    const audio = h.runtime.getTracks().audio;
    await h.runtime.leaveAgoraCall("LEAVE");
    await h.runtime.leaveAgoraCall("LEAVE");
    assert.equal(h.calls.filter(c => c === "stop:mic").length, 1);
    assert.equal(h.calls.filter(c => c === "close:mic").length, 1);
    assert.equal(audio.kind, "mic");
});

test("Gate C R7 stale cleanup after new generation cannot close current resource", async () => {
    const h = productionHarness({ cameraDeferred: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isCameraReady(); i++) await Promise.resolve();
    h.runtime.activateReliabilitySession("session-new", "channel-a");
    const currentRun = h.runtime.joinAgoraCall("channel-a", "session-new");
    const currentResult = await currentRun;
    assert.equal(currentResult.status, "CONNECTED_AV");
    const trackB = h.runtime.getTracks().audio;
    assert.ok(trackB);
    h.resolveCameraTrack(h.track("stale-camera"));
    assert.equal((await pending).status, "STALE");
    assert.equal(h.runtime.getReliability().session.id, "session-new");
    assert.equal(h.calls.filter(c => c === "close:stale-camera").length, 1);
    assert.equal(h.calls.filter(c => c === "stop:stale-camera").length, 1);
    assert.equal(trackB.stopped, false);
    assert.equal(trackB.closed, false);
    assert.equal(h.runtime.getTracks().audio, trackB);
    assert.equal(h.runtime.getReliability().currentCallFlow.sessionId, "session-new");
});

test("Gate C R8 recovery publication is one-shot", async () => {
    const stomp = { connected: true, sends: [], send(destination) { this.sends.push(destination); } };
    const h = productionHarness({ mic: false, stompClient: stomp });
    h.runtime.activateReliabilitySession("session-a", "channel-a", { recovery: true });
    assert.equal((await h.runtime.joinAgoraCall("channel-a", "session-a")).status, "FAILED_MICROPHONE");
    assert.deepEqual(stomp.sends, ["/app/recovery-failed"]);
});

test("Gate C R9 recovery publication invalidated immediately before send", async () => {
    const stomp = { connected: true, sends: [], send(destination) { this.sends.push(destination); } };
    const h = productionHarness({ micDeferred: true, stompClient: stomp });
    h.runtime.activateReliabilitySession("session-a", "channel-a", { recovery: true });
    const pending = h.runtime.joinAgoraCall("channel-a", "session-a");
    for (let i = 0; i < 200 && !h.isMicReady(); i++) await Promise.resolve();
    h.runtime.getReliability().markTerminal();
    h.rejectMicTrack(new Error("late mic"));
    h.resolveMicTrack(h.track("late-mic"));
    assert.equal((await pending).status, "STALE");
    assert.deepEqual(stomp.sends, []);
});

test("Gate 2B unsupported system requirements block pre-call without SDK or lifecycle mutation", async () => {
    const h = productionHarness();
    h.agora.checkSystemRequirements = () => false;
    const ready = await h.runtime.getDeviceControls().preparePreCall();
    assert.equal(ready, false);
    assert.equal(h.elements.get("device-status").innerText.includes("không hỗ trợ"), true);
    assert.equal(h.calls.some(c => c.startsWith("Agora.get") || c.startsWith("Agora.create")), false);
    assert.equal(h.calls.some(c => c.includes("/api/") || c === "Agora.join" || c.includes("publish")), false);
});

test("Gate 2B camera unplug reports audio-only and replug switches the surviving track", async () => {
    const h = productionHarness();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const current = h.runtime.getTracks().video;
    const audio = h.runtime.getTracks().audio;
    const audioOwner = audio.__ownerOperationId;
    h.devices.camera = [];
    h.agora.emit("camera-changed");
    await flushMicrotasks(500);
    assert.equal(h.runtime.getTracks().video, null);
    assert.equal(current.stopped, true);
    assert.equal(current.closed, true);
    assert.equal(h.calls.filter(c => c === "Agora.unpublish:1").length, 1);
    assert.equal(h.calls.filter(c => c === "stop:camera").length, 1);
    assert.equal(h.calls.filter(c => c === "close:camera").length, 1);
    assert.equal(h.calls.filter(c => c === "stop:mic" || c === "close:mic").length, 0);
    assert.equal(h.calls.filter(c => c === "Agora.unpublish:2").length, 0);
    assert.equal(h.calls.filter(c => c === "Agora.publish:1").length, 0);
    assert.equal(h.calls.filter(c => c === "Agora.publish:2").length, 1);
    assert.equal(h.runtime.getReliability().cleanupDone.has(current), true);
    assert.equal(h.runtime.getTracks().audio, audio);
    assert.equal(audio.__ownerOperationId, audioOwner);
    assert.equal(h.elements.get("device-status").innerText.length > 0, true);
    h.agora.emit("camera-changed");
    await flushMicrotasks();
    assert.equal(h.calls.filter(c => c === "Agora.unpublish:1").length, 1);
    assert.equal(h.calls.filter(c => c === "stop:camera").length, 1);
    assert.equal(h.calls.filter(c => c === "close:camera").length, 1);
    assert.equal(h.calls.filter(c => c === "stop:mic" || c === "close:mic").length, 0);
    assert.equal(h.calls.filter(c => c === "Agora.createCamera").length, 1);
    h.devices.camera = [{ deviceId: "cam-b", label: "Camera B" }];
    h.agora.emit("camera-changed");
    await flushMicrotasks();
    assert.equal(h.runtime.getDeviceControls().snapshot().selected.camera, "cam-b");
    assert.equal(h.runtime.getTracks().video, null);
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, false);
    assert.equal(h.calls.some(c => c.includes("/leave-agora") || c.includes("recovery-failed")), false);
});

test("Gate 2B stale camera-remove callback with same track is rejected by generation context", async () => {
    const h = productionHarness();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const video = h.runtime.getTracks().video;
    const audio = h.runtime.getTracks().audio;
    const audioOwner = audio.__ownerOperationId;
    h.devices.camera = [];
    h.agora.emit("camera-changed");
    h.runtime.activateReliabilitySession("session-new", "channel-new");
    await flushMicrotasks();
    assert.equal(h.calls.filter(c => c === "Agora.unpublish:1").length, 0);
    assert.equal(h.calls.filter(c => c === "stop:camera" || c === "close:camera").length, 0);
    assert.equal(h.runtime.getTracks().video, video);
    assert.equal(video.stopped, false);
    assert.equal(video.closed, false);
    assert.equal(h.runtime.getTracks().audio, audio);
    assert.equal(audio.__ownerOperationId, audioOwner);
    assert.equal(h.runtime.getReliability().session.id, "session-new");
});

test("Gate 2B playback switch rejection preserves call ownership and falls back to default output", async () => {
    const h = productionHarness();
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    const remote = { setPlaybackDevice() { throw new Error("speaker busy"); }, play() {} };
    h.client.subscribe = async () => {};
    await h.client.handlers.get("user-published")({ uid: "peer-a", audioTrack: remote }, "audio");
    h.devices.playback = [{ deviceId: "speaker-b", label: "Speaker B" }];
    h.agora.emit("playback-device-changed");
    await flushMicrotasks();
    assert.equal(h.runtime.getTracks().audio.stopped, false);
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, false);
    assert.equal(h.elements.get("device-status").innerText.includes("Camera"), true);
    assert.equal(h.calls.filter(c => c === "Agora.publish:1" || c === "Agora.publish:2").length, 1);
    const fallback = productionHarness();
    await fallback.runtime.joinAgoraCall("channel-a", "session-a");
    fallback.devices.playback = [{ deviceId: "speaker-b", label: "Speaker B" }];
    fallback.agora.emit("playback-device-changed");
    await flushMicrotasks();
    assert.equal(fallback.elements.get("device-status").innerText.includes("mặc định"), true);
});

test("Gate 2B autoplay listeners are deduplicated and disposed with terminal media cleanup", async () => {
    const h = productionHarness();
    assert.equal(h.listenerCalls.filter(c => c === "on:camera-changed").length, 1);
    assert.equal(h.listenerCalls.filter(c => c === "on:microphone-changed").length, 1);
    assert.equal(h.listenerCalls.filter(c => c === "on:playback-device-changed").length, 1);
    h.runtime.getDeviceControls().dispose();
    h.runtime.getDeviceControls().dispose();
    assert.equal(h.listenerCalls.filter(c => c === "off:camera-changed").length, 1);
    assert.equal(h.listenerCalls.filter(c => c === "off:microphone-changed").length, 1);
    assert.equal(h.listenerCalls.filter(c => c === "off:playback-device-changed").length, 1);
});

test("Gate 2B autoplay resume success uses one resume and no retry timer after play succeeds", async () => {
    const h = productionHarness();
    const remote = { playCalls: 0, play() { this.playCalls++; return Promise.resolve(); } };
    h.client.subscribe = async () => {};
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    await h.client.handlers.get("user-published")({ uid: "peer-a", audioTrack: remote }, "audio");
    h.agora.emit("autoplay-failed", { type: "audio" });
    await h.runtime.retryBlockedAudio();
    assert.equal(h.calls.filter(c => c === "Agora.resumeAudioContext").length, 1);
    assert.equal(remote.playCalls, 2);
    assert.equal(h.timers.length, 0);
    assert.equal(h.elements.get("enable-audio-btn").style.display, "none");
});

test("Gate 2B autoplay retry failure is deterministic and leave closes owned tracks once", async () => {
    const queued = [];
    const h = productionHarness({ setTimeoutImpl: (fn, ms) => { const entry = { fn, ms }; queued.push(entry); return entry; } });
    const remote = { playCalls: 0, play() { this.playCalls++; return Promise.reject(new Error("blocked")); } };
    h.client.subscribe = async () => {};
    await h.runtime.joinAgoraCall("channel-a", "session-a");
    await h.client.handlers.get("user-published")({ uid: "peer-a", audioTrack: remote }, "audio");
    h.agora.emit("autoplay-failed", { type: "audio" });
    const retry = h.runtime.retryBlockedAudio();
    await flushMicrotasks();
    const retryTimer = queued.find(entry => entry.ms === 250);
    assert.ok(retryTimer);
    retryTimer.fn();
    await retry;
    assert.equal(remote.playCalls, 2);
    assert.equal(h.elements.get("enable-audio-btn").style.display, "");
    await h.runtime.leaveAgoraCall("LEAVE");
    await h.runtime.leaveAgoraCall("LEAVE");
    assert.equal(h.calls.filter(c => c === "stop:mic").length, 1);
    assert.equal(h.calls.filter(c => c === "close:mic").length, 1);
    assert.equal(h.runtime.getReliability().terminalOrLeaveLatch, true);
});

for (const [label, invalidate] of [
    ["terminal", h => h.runtime.getReliability().markTerminal()],
    ["leave", h => h.runtime.leaveAgoraCall("LEAVE")],
    ["new generation", h => h.runtime.activateReliabilitySession("session-new", "channel-new")]
]) {
    test(`Gate 2B playback pending ${label} cannot commit stale selection`, async () => {
        const h = productionHarness();
        const gate = deferred();
        const remote = { setPlaybackDevice() { return gate.promise; }, play() {} };
        h.client.subscribe = async () => {};
        await h.runtime.getDeviceControls().preparePreCall();
        await h.runtime.joinAgoraCall("channel-a", "session-a");
        await h.client.handlers.get("user-published")({ uid: "peer-a", audioTrack: remote }, "audio");
        h.devices.playback = [{ deviceId: "speaker-b", label: "Speaker B" }];
        const before = h.runtime.getDeviceControls().snapshot().selected.playback;
        h.agora.emit("playback-device-changed");
        await flushMicrotasks();
        await invalidate(h);
        gate.resolve();
        await flushMicrotasks();
        assert.equal(h.runtime.getDeviceControls().snapshot().selected.playback, before);
        assert.equal(h.calls.filter(c => c.startsWith("Agora.publish") || c.startsWith("Agora.unpublish")).length, 1);
    });
}

module.exports = async function runFlowTests(report) {
    for (const item of tests) await report(item.name, item.fn);
};
