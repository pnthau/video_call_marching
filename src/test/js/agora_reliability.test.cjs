"use strict";

const assert = require("node:assert/strict");
const { AgoraReliabilityController, AdaptiveMediaController, DEADLINE_MS, classifyAgoraError } = require("../../main/resources/static/js/agora_reliability.js");

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

class FakeClock {
    constructor() { this.time = 0; this.next = 1; this.timers = new Map(); this.cleared = []; }
    now() { return this.time; }
    setTimeout(fn, delay) { const id = this.next++; this.timers.set(id, { at: this.time + delay, fn }); return id; }
    clearTimeout(id) { this.cleared.push(id); this.timers.delete(id); }
    tick(ms) {
        this.time += ms;
        const due = [...this.timers].filter(([, timer]) => timer.at <= this.time).sort((a, b) => a[1].at - b[1].at);
        due.forEach(([id, timer]) => { if (this.timers.delete(id)) timer.fn(); });
    }
}

class FakeClient {
    constructor() { this.handlers = new Map(); this.connectionState = "CONNECTED"; this.renewCalls = 0; this.joinCalls = 0; this.leaveCalls = 0; this.renewDeferred = null; }
    on(event, handler) { const handlers = this.handlers.get(event) || []; handlers.push(handler); this.handlers.set(event, handlers); }
    off(event, handler) { this.handlers.set(event, (this.handlers.get(event) || []).filter(item => item !== handler)); }
    emit(event, ...args) { (this.handlers.get(event) || []).slice().forEach(handler => handler(...args)); }
    renewToken() { this.renewCalls++; return this.renewDeferred ? this.renewDeferred.promise : Promise.resolve(); }
    join() { this.joinCalls++; this.connectionState = "CONNECTED"; return Promise.resolve("uid"); }
    leave() { this.leaveCalls++; this.connectionState = "DISCONNECTED"; return Promise.resolve(); }
}

function harness(overrides = {}) {
    const clock = new FakeClock();
    const client = overrides.client || new FakeClient();
    const states = [];
    const diagnostics = [];
    let tokenFetches = 0;
    const controller = new AgoraReliabilityController({
        clock,
        client,
        appId: "app-id",
        createClient: () => new FakeClient(),
        random: () => 0,
        fetchToken: overrides.fetchToken || (async () => { tokenFetches++; return { status: 200, ok: true, data: { token: "opaque" } }; }),
        isAuthorized: overrides.isAuthorized || (() => true),
        onState: value => states.push(value),
        onDiagnostic: value => diagnostics.push(value)
    });
    controller.activate({ id: "session-a", terminal: false });
    return { controller, clock, client, states, diagnostics, tokenFetches: () => tokenFetches };
}

const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

test("ordinary resolve before deadline commits", async () => {
    const h = harness(); let value = null;
    await h.controller.ordinary("CREATE_MIC_TRACK", "mic", async () => ({ close() {} }), result => { value = result; });
    assert.ok(value); assert.equal(h.controller.rtcState, "IDLE");
});

test("ordinary reject before deadline fails current state", async () => {
    const h = harness();
    await h.controller.ordinary("CREATE_MIC_TRACK", "mic", async () => { throw Object.assign(new Error("private"), { code: "NOT_READABLE" }); }, () => {});
    assert.equal(h.controller.rtcState, "FAILED");
});

test("late resolve leaves UI failed and closes created track once", async () => {
    const h = harness(); const d = deferred(); let closes = 0; let commits = 0;
    const pending = h.controller.ordinary("CREATE_MIC_TRACK", "mic", () => d.promise, () => commits++);
    h.clock.tick(DEADLINE_MS); d.resolve({ stop() {}, close() { closes++; } }); await pending;
    assert.equal(h.controller.rtcState, "FAILED"); assert.equal(commits, 0); assert.equal(closes, 1);
});

test("late reject does not change newer state and is sanitized", async () => {
    const h = harness(); const d = deferred();
    const pending = h.controller.ordinary("TOKEN_FETCH", "token", () => d.promise, () => {});
    h.clock.tick(DEADLINE_MS); h.controller.setRtcState("CONNECTED"); d.reject(new Error("secret raw token")); await pending;
    assert.equal(h.controller.rtcState, "CONNECTED"); assert.equal(h.diagnostics.at(-1).code, "UNKNOWN"); assert.equal(h.diagnostics.at(-1).stale, true);
    assert.equal(JSON.stringify(h.diagnostics).includes("secret raw token"), false);
});

test("retry generation cannot be overwritten by old callback", async () => {
    const h = harness(); const old = deferred(); let oldCommits = 0; let newCommits = 0;
    const first = h.controller.ordinary("CREATE_CAMERA_TRACK", "camera", () => old.promise, () => oldCommits++, true);
    h.clock.tick(DEADLINE_MS);
    await h.controller.ordinary("CREATE_CAMERA_TRACK", "camera", async () => ({ close() {} }), () => newCommits++, true);
    old.resolve({ close() {} }); await first;
    assert.equal(oldCommits, 0); assert.equal(newCommits, 1);
});

test("old callback does not clear retry timer", async () => {
    const h = harness(); const old = deferred(); const newer = deferred();
    const first = h.controller.ordinary("CREATE_CAMERA_TRACK", "camera", () => old.promise, () => {});
    const oldOp = h.controller.currentOwners.get("camera");
    const second = h.controller.ordinary("CREATE_CAMERA_TRACK", "camera", () => newer.promise, () => {});
    const newOp = h.controller.currentOwners.get("camera");
    old.resolve({ close() {} }); await first;
    assert.ok(h.clock.timers.has(newOp.timer)); assert.notEqual(oldOp.operationId, newOp.operationId);
    newer.resolve({ close() {} }); await second;
});

test("stale cleanup never closes current-generation resource", async () => {
    const h = harness(); const old = deferred(); let currentClose = 0;
    const pending = h.controller.ordinary("CREATE_MIC_TRACK", "mic", () => old.promise, () => {});
    h.clock.tick(DEADLINE_MS);
    const current = { close() { currentClose++; } };
    await h.controller.ordinary("CREATE_MIC_TRACK", "mic", async () => current, () => {}, true);
    old.resolve(current); await pending;
    assert.equal(currentClose, 0);
});

test("terminal while pending revokes and prevents late commit", async () => {
    const h = harness(); const d = deferred(); let commits = 0;
    const pending = h.controller.ordinary("CREATE_MIC_TRACK", "mic", () => d.promise, () => commits++);
    h.controller.markTerminal(); d.resolve({ close() {} }); await pending;
    assert.equal(commits, 0); assert.equal(h.controller.terminalOrLeaveLatch, true);
});

test("leave/dispose pending cleanup is idempotent", async () => {
    const h = harness(); const d = deferred(); let closes = 0;
    const resource = { close() { closes++; } };
    const pending = h.controller.ordinary("CREATE_MIC_TRACK", "mic", () => d.promise, () => {});
    const dispose1 = h.controller.dispose("LEAVE"); const dispose2 = h.controller.dispose("LEAVE");
    d.resolve(resource); await Promise.all([pending, dispose1, dispose2]);
    assert.equal(h.client.leaveCalls, 1); assert.equal(closes, 1);
});

test("renew success and concurrent events are single-flight", async () => {
    const h = harness(); const fetch = deferred(); let fetches = 0;
    h.controller.fetchToken = async () => { fetches++; return fetch.promise; };
    const first = h.controller.renewToken(); const second = h.controller.renewToken();
    fetch.resolve({ status: 200, ok: true, data: { token: "opaque" } });
    assert.equal(await first, true); assert.equal(await second, true); assert.equal(fetches, 1); assert.equal(h.client.renewCalls, 1);
});

test("renew rejection retires unknown-token client", async () => {
    const h = harness(); h.client.renewDeferred = deferred();
    const pending = h.controller.renewToken(); h.client.renewDeferred.reject(new Error("raw token"));
    assert.equal(await pending, false); assert.equal(h.client.leaveCalls, 1); assert.notEqual(h.controller.client, h.client);
    assert.equal([...h.controller.settlementMarkers.values()].at(-1), "QUARANTINED_RETIRED");
    assert.equal(JSON.stringify(h.diagnostics).includes("raw token"), false);
});

test("terminal 409 revokes renewal without SDK call", async () => {
    const h = harness({ fetchToken: async () => ({ status: 409, ok: false }) });
    assert.equal(await h.controller.renewToken(), false); assert.equal(h.client.renewCalls, 0); assert.equal(h.controller.terminalOrLeaveLatch, true);
});

test("did-expire performs serialized fresh-token transport recovery", async () => {
    const h = harness();
    assert.equal(await h.controller.recoverExpiredToken(), true);
    assert.equal(h.client.leaveCalls, 1); assert.equal(h.client.joinCalls, 1); assert.equal(h.controller.rtcState, "CONNECTED");
});

test("unauthorized token response does not call SDK or domain lifecycle", async () => {
    const h = harness({ fetchToken: async () => ({ status: 403, ok: false }) });
    assert.equal(await h.controller.renewToken(), false); assert.equal(h.client.renewCalls, 0); assert.equal(h.controller.terminalOrLeaveLatch, true);
});

test("old token response cannot renew replacement client", async () => {
    const old = new FakeClient(); const h = harness({ client: old }); const fetch = deferred();
    h.controller.fetchToken = () => fetch.promise;
    const pending = h.controller.renewToken();
    h.controller.invalidate("CLIENT_REPLACED", { replaceClient: true });
    h.controller.client = new FakeClient(); h.controller.bindClient(h.controller.client);
    fetch.resolve({ status: 200, ok: true, data: { token: "opaque" } });
    assert.equal(await pending, false); assert.equal(old.renewCalls, 0); assert.equal(h.controller.client.renewCalls, 0);
});

test("connection mapping distinguishes leave, reconnect, and failure", async () => {
    const h = harness();
    h.client.emit("connection-state-change", "RECONNECTING", "CONNECTED"); assert.equal(h.controller.rtcState, "RECONNECTING");
    h.client.emit("connection-state-change", "CONNECTED", "RECONNECTING"); assert.equal(h.controller.rtcState, "CONNECTED");
    h.client.emit("connection-state-change", "DISCONNECTED", "DISCONNECTING", "LEAVE"); assert.equal(h.controller.rtcState, "IDLE");
    h.client.emit("peerconnection-state-change", "failed", "connected"); assert.equal(h.controller.rtcState, "FAILED");
});

test("unknown exception records only sanitized default", async () => {
    const h = harness(); h.client.emit("exception", { code: 9999, msg: "secret channel token", data: "private" });
    assert.deepEqual(h.diagnostics.at(-1), { operationType: "EXCEPTION", eventCategory: "AGORA_EXCEPTION_UNKNOWN", code: 9999, stale: false });
});

test("listeners register once and are removed on dispose", async () => {
    const h = harness(); h.controller.bindClient(h.client);
    assert.equal(h.client.handlers.get("token-privilege-will-expire").length, 1);
    await h.controller.dispose();
    for (const handlers of h.client.handlers.values()) assert.equal(handlers.length, 0);
});

test("duplicate resource cleanup closes once", async () => {
    const h = harness(); let closes = 0; const op = h.controller.startOperation("CREATE_MIC_TRACK", "mic", h.client, false);
    const resource = { __ownerOperationId: op.operationId, close() { closes++; } };
    h.controller.cleanupResource(op, resource); h.controller.cleanupResource(op, resource);
    assert.equal(closes, 1);
});

test("exact deadline boundary is timeout regardless of timer callback order", async () => {
    for (const runTimerFirst of [false, true]) {
        const h = harness(); const d = deferred(); let commits = 0;
        const pending = h.controller.ordinary("CREATE_MIC_TRACK", "boundary", () => d.promise, () => commits++);
        if (runTimerFirst) h.clock.tick(DEADLINE_MS); else h.clock.time = DEADLINE_MS;
        d.resolve({ close() {} }); await pending;
        if (!runTimerFirst) h.clock.tick(0);
        assert.equal(commits, 0); assert.equal(h.controller.rtcState, "FAILED");
    }
});

test("exact-object queue spans operation types and max concurrency is one", async () => {
    const h = harness(); const firstGate = deferred(); let concurrent = 0; let max = 0; const order = [];
    const invoke = name => async () => { concurrent++; max = Math.max(max, concurrent); order.push(`${name}-start`); if (name === "join") await firstGate.promise; concurrent--; order.push(`${name}-end`); };
    const first = h.controller.shared("JOIN", "join-scope", h.client, invoke("join"), async () => true, () => {}, false);
    await Promise.resolve();
    const second = h.controller.shared("TOKEN_RENEW", "renew-scope", h.client, invoke("renew"), async () => true, () => {}, false);
    await Promise.resolve(); assert.deepEqual(order, ["join-start"]);
    firstGate.resolve(); await Promise.all([first, second]);
    assert.equal(max, 1); assert.deepEqual(order, ["join-start", "join-end", "renew-start", "renew-end"]);
});

test("queued intent is tombstoned by latest intent and terminal drops queue", async () => {
    const h = harness(); const gate = deferred(); let oldCalls = 0; let latestCalls = 0;
    const blocker = h.controller.shared("JOIN", "transport", h.client, () => gate.promise, async () => true, () => {}, false);
    await Promise.resolve();
    const old = h.controller.shared("JOIN", "retry", h.client, async () => { oldCalls++; }, async () => true, () => {}, false);
    const latest = h.controller.shared("JOIN", "retry", h.client, async () => { latestCalls++; }, async () => true, () => {}, false);
    h.controller.markTerminal(); gate.resolve(); await Promise.all([blocker, old, latest]);
    assert.equal(oldCalls, 0); assert.equal(latestCalls, 0);
});

test("mutate-then-reject join reconciles actual client before SAFE marker", async () => {
    const h = harness();
    const result = await h.controller.shared("JOIN", "transport", h.client, async exact => {
        exact.connectionState = "CONNECTED"; throw Object.assign(new Error(), { code: "NETWORK_ERROR" });
    }, async (exact) => { await exact.leave(); return exact.connectionState === "DISCONNECTED"; }, () => assert.fail("must not commit"), false);
    assert.equal(result, null); assert.equal(h.client.connectionState, "DISCONNECTED");
    assert.equal([...h.controller.settlementMarkers.values()].at(-1), "SAFE_VERIFIED");
});

test("mutate-then-reject setDevice reconciles exact track and stages no UI", async () => {
    const h = harness(); const track = { device: "old", setDevice(value) { this.device = value; return Promise.resolve(); }, close() {} }; let commits = 0;
    await h.controller.shared("DEVICE_SWITCH", "camera-device", track, async exact => {
        exact.device = "wrong"; throw Object.assign(new Error(), { code: "NOT_READABLE" });
    }, async exact => { await exact.setDevice("latest"); return exact.device === "latest"; }, () => commits++, false);
    assert.equal(track.device, "latest"); assert.equal(commits, 0);
    assert.equal([...h.controller.settlementMarkers.values()].at(-1), "SAFE_VERIFIED");
});

test("shared success remains staged until reconciliation completes", async () => {
    const h = harness(); const reconciliation = deferred(); let commits = 0;
    const pending = h.controller.shared("JOIN", "transport", h.client, async () => "uid", () => reconciliation.promise, () => commits++, false);
    await Promise.resolve(); await Promise.resolve(); assert.equal(commits, 0);
    reconciliation.resolve(true); await pending; assert.equal(commits, 1);
});

test("reconciliation failure quarantines once before next intent", async () => {
    const h = harness(); let closes = 0; const track = { close() { closes++; } };
    await h.controller.shared("DEVICE_SWITCH", "device", track, async () => {}, async () => false, () => assert.fail(), false);
    const marker = [...h.controller.settlementMarkers.values()].at(-1);
    await h.controller.retireClientOrResource(track, null);
    assert.equal(marker, "QUARANTINED_RETIRED"); assert.equal(closes, 1);
});

test("late publish and subscribe compensate exact stale side effects", async () => {
    const h = harness(); const publish = deferred(); const subscribe = deferred(); let unpublish = 0; let unsubscribe = 0;
    h.client.unpublish = async () => { unpublish++; }; h.client.unsubscribe = async () => { unsubscribe++; };
    const p1 = h.controller.shared("PUBLISH", "publish", h.client, () => publish.promise,
        async exact => { await exact.unpublish(); return true; }, () => assert.fail(), false);
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); h.clock.tick(DEADLINE_MS); publish.resolve(); await p1;
    const p2 = h.controller.shared("SUBSCRIBE", "subscribe", h.client, () => subscribe.promise,
        async exact => { await exact.unsubscribe(); return true; }, () => assert.fail(), false);
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); h.clock.tick(DEADLINE_MS); subscribe.resolve(); await p2;
    assert.equal(unpublish, 1); assert.equal(unsubscribe, 1);
});

test("token retry uses three bounded attempts and then fails", async () => {
    const h = harness(); let calls = 0;
    h.controller.fetchToken = async () => { calls++; return { status: 503, ok: false }; };
    const pending = h.controller.renewToken();
    await Promise.resolve(); await Promise.resolve(); h.clock.tick(500);
    await Promise.resolve(); await Promise.resolve(); h.clock.tick(1000);
    assert.equal(await pending, false); assert.equal(calls, 3); assert.equal(h.client.renewCalls, 0);
});

test("did-expire republishes retained tracks exactly once", async () => {
    const h = harness(); let publishes = 0; const tracks = [{}, {}];
    h.client.publish = async value => { publishes++; assert.deepEqual(value, tracks); };
    h.controller.getRetainedTracks = () => tracks;
    assert.equal(await h.controller.recoverExpiredToken(), true); assert.equal(publishes, 1);
});

test("connection reason matrix and per-UID media deadlines are independent", async () => {
    const h = harness();
    h.controller.onConnection("DISCONNECTED", "CONNECTED", "UID_BANNED"); assert.equal(h.controller.rtcState, "FAILED");
    h.controller.onConnection("DISCONNECTED", "DISCONNECTING", "LEAVE"); assert.equal(h.controller.rtcState, "IDLE");
    h.controller.onPeerConnection("disconnected"); assert.equal(h.controller.rtcState, "RECONNECTING");
    h.controller.onMediaReconnectStart("u1"); h.controller.onMediaReconnectStart("u2");
    h.controller.onMediaReconnectEnd("u1"); assert.equal(h.controller.mediaReconnectUids.has("u2"), true);
    h.clock.tick(DEADLINE_MS); assert.equal(h.controller.mediaReconnectState, "FAILED");
});

test("terminal cleanup revokes owners removes listeners and replaces client", async () => {
    const h = harness(); const d = deferred(); let closes = 0;
    const pending = h.controller.ordinary("CREATE_MIC_TRACK", "mic", () => d.promise, () => assert.fail());
    const cleanup = h.controller.cleanupCall([{ close() { closes++; } }], "TERMINAL");
    d.resolve({ close() { closes++; } }); await Promise.all([pending, cleanup]);
    assert.equal(h.controller.rtcState, "IDLE"); assert.notEqual(h.controller.client, h.client); assert.equal(closes, 2);
    for (const handlers of h.client.handlers.values()) assert.equal(handlers.length, 0);
});

test("settlement markers are mutually exclusive and duplicate-idempotent", async () => {
    const h = harness(); const op = h.controller.startOperation("JOIN", "marker", h.client, false);
    assert.equal(h.controller.recordMarker(op, "SAFE_VERIFIED"), true);
    assert.equal(h.controller.recordMarker(op, "SAFE_VERIFIED"), true);
    assert.equal(h.controller.recordMarker(op, "QUARANTINED_RETIRED"), false);
    assert.equal(h.controller.settlementMarkers.get(op.operationId), "SAFE_VERIFIED");
});

test("retirement continues cleanup steps after an earlier failure", async () => {
    const h = harness(); let closed = 0;
    const resource = { stop() { throw Object.assign(new Error(), { code: "NOT_READABLE" }); }, close() { closed++; } };
    await h.controller.retireClientOrResource(resource, null);
    assert.equal(closed, 1); assert.equal(h.diagnostics.at(-1).code, "NOT_READABLE");
});

test("retryable token fetch succeeds on second attempt with backoff", async () => {
    const h = harness(); let calls = 0;
    h.controller.fetchToken = async () => ++calls === 1 ? { status: 503, ok: false } : { status: 200, ok: true, data: { token: "opaque" } };
    const pending = h.controller.renewToken(); await Promise.resolve(); await Promise.resolve(); h.clock.tick(500);
    assert.equal(await pending, true); assert.equal(calls, 2); assert.equal(h.client.renewCalls, 1);
});

test("token identity mismatch is rejected before join or renew", async () => {
    const h = harness(); h.controller.session.channelName = "expected";
    h.controller.fetchToken = async () => ({ status: 200, ok: true, data: { token: "opaque", channelName: "wrong", uid: "7" } });
    assert.equal(await h.controller.fetchInitialToken(), null); assert.equal(h.client.joinCalls, 0); assert.equal(h.client.renewCalls, 0);
});

test("old-generation listeners cannot mutate after client replacement", async () => {
    const h = harness(); const old = h.client; let customCalls = 0;
    h.controller.registerClientHandler("user-unpublished", () => customCalls++);
    await h.controller.cleanupCall([], "LEAVE");
    old.emit("user-unpublished", {}, "video"); assert.equal(customCalls, 0);
    h.controller.activate({ id: "session-b", terminal: false });
    h.controller.client.emit("user-unpublished", {}, "audio"); assert.equal(customCalls, 1);
});

test("dequeue auth or exact-object invalidation makes zero SDK calls", async () => {
    for (const invalidKind of ["auth", "object"]) {
        let authorized = true; let exact = true; let calls = 0;
        const h = harness({ isAuthorized: () => authorized });
        h.controller.isExactObjectCurrent = () => exact;
        const blocker = deferred();
        const first = h.controller.shared("JOIN", "first", h.client, () => blocker.promise, async () => true, () => {}, false);
        await Promise.resolve(); await Promise.resolve();
        const queued = h.controller.shared("TOKEN_RENEW", "second", h.client, async () => { calls++; }, async () => true, () => {}, false);
        if (invalidKind === "auth") authorized = false; else exact = false;
        blocker.resolve(); await Promise.all([first, queued]);
        assert.equal(calls, 0, invalidKind);
    }
});

test("queue wait beyond deadline arms no timer until acquire then one fresh timer", async () => {
    const h = harness(); const firstGate = deferred(); const secondGate = deferred();
    const first = h.controller.shared("JOIN", "first", h.client, () => firstGate.promise, async () => true, () => {}, false);
    await Promise.resolve(); await Promise.resolve();
    const beforeQueueTimers = h.clock.timers.size;
    const second = h.controller.shared("TOKEN_RENEW", "second", h.client, () => secondGate.promise, async () => true, () => {}, false);
    assert.equal(h.clock.timers.size, beforeQueueTimers);
    h.clock.tick(DEADLINE_MS + 5000); assert.equal(h.clock.timers.size, 0);
    firstGate.resolve(); await first; await Promise.resolve(); await Promise.resolve();
    assert.equal(h.clock.timers.size, 1);
    const secondOp = h.controller.currentOwners.get("second");
    assert.equal(secondOp.deadlineAt - secondOp.startedAt, DEADLINE_MS);
    secondGate.resolve(); await second;
});

test("shared mutate-then-reject after timeout reconciles join device and renew", async () => {
    const cases = [
        { type: "JOIN", object: () => new FakeClient(), mutate: object => { object.connectionState = "CONNECTED"; }, reconcile: async object => { await object.leave(); return true; }, marker: "SAFE_VERIFIED" },
        { type: "DEVICE_SWITCH", object: () => ({ device: "old", close() {}, setDevice(value) { this.device = value; } }), mutate: object => { object.device = "stale"; }, reconcile: async object => { object.setDevice("latest"); return object.device === "latest"; }, marker: "SAFE_VERIFIED" },
        { type: "TOKEN_RENEW", object: () => new FakeClient(), mutate: object => { object.tokenIdentity = "unknown"; }, reconcile: async () => false, marker: "QUARANTINED_RETIRED" }
    ];
    for (const item of cases) {
        const h = harness(); const object = item.object(); const gate = deferred();
        if (item.type !== "DEVICE_SWITCH") h.controller.client = object;
        const pending = h.controller.shared(item.type, `scope-${item.type}`, object, async exact => {
            await gate.promise; item.mutate(exact); throw Object.assign(new Error(), { code: "NETWORK_ERROR" });
        }, item.reconcile, () => assert.fail(item.type), false);
        await Promise.resolve(); await Promise.resolve(); h.clock.tick(DEADLINE_MS); gate.resolve(); await pending;
        assert.equal([...h.controller.settlementMarkers.values()].at(-1), item.marker, item.type);
        if (item.type === "JOIN") { assert.equal(object.connectionState, "DISCONNECTED"); assert.equal(object.leaveCalls, 1); }
        if (item.type === "DEVICE_SWITCH") assert.equal(object.device, "latest");
        if (item.type === "TOKEN_RENEW") { assert.equal(object.connectionState, "DISCONNECTED"); assert.equal(object.leaveCalls, 1); assert.notEqual(h.controller.client, object); }
    }
});

test("terminal during shared mutations prevents commit and reconciles final SDK state", async () => {
    for (const type of ["JOIN", "DEVICE_SWITCH", "TOKEN_RENEW"]) {
        const h = harness(); const gate = deferred(); let commits = 0;
        const object = type === "DEVICE_SWITCH" ? { device: "old", close() {} } : h.client;
        const pending = h.controller.shared(type, `terminal-${type}`, object, async exact => {
            await gate.promise;
            if (type === "DEVICE_SWITCH") exact.device = "stale"; else exact.connectionState = "CONNECTED";
            throw Object.assign(new Error(), { code: "NETWORK_ERROR" });
        }, async exact => {
            if (type === "TOKEN_RENEW") return false;
            if (type === "DEVICE_SWITCH") { exact.device = "safe"; return true; }
            await exact.leave(); return exact.connectionState === "DISCONNECTED";
        }, () => commits++, false);
        await Promise.resolve(); await Promise.resolve(); h.controller.markTerminal(); gate.resolve(); await pending;
        assert.equal(commits, 0, type);
        const marker = [...h.controller.settlementMarkers.values()].at(-1);
        if (type === "JOIN") { assert.equal(object.connectionState, "DISCONNECTED"); assert.equal(marker, "SAFE_VERIFIED"); }
        if (type === "DEVICE_SWITCH") { assert.equal(object.device, "safe"); assert.equal(marker, "SAFE_VERIFIED"); }
        if (type === "TOKEN_RENEW") { assert.equal(object.connectionState, "DISCONNECTED"); assert.equal(marker, "QUARANTINED_RETIRED"); }
    }
});

test("failure reconcile marker release next ordering is strict", async () => {
    const order = []; const h = harness(); const gate = deferred();
    h.controller.onState = state => { if (state.rtcConnectionState === "FAILED") order.push("failure"); };
    const originalMarker = h.controller.recordMarker.bind(h.controller);
    h.controller.recordMarker = (op, marker) => { order.push(`marker:${marker}`); return originalMarker(op, marker); };
    const first = h.controller.shared("JOIN", "first", h.client, async () => { await gate.promise; throw Object.assign(new Error(), { code: "NETWORK_ERROR" }); },
        async exact => { order.push("reconcile"); await exact.leave(); return true; }, () => {}, false);
    await Promise.resolve(); await Promise.resolve();
    const second = h.controller.shared("TOKEN_RENEW", "second", h.client, async () => { order.push("next"); }, async () => true, () => {}, false);
    gate.resolve(); await Promise.all([first, second]);
    assert.deepEqual(order.slice(0, 4), ["failure", "reconcile", "marker:SAFE_VERIFIED", "next"]);
});

test("all approved fatal reasons fail, token/network recover, unknown fails", async () => {
    const fatal = ["SERVER_ERROR", "UID_BANNED", "IP_BANNED", "CHANNEL_BANNED", "LICENSE_MISSING", "LICENSE_EXPIRED", "LICENSE_MINUTES_EXCEEDED", "LICENSE_PERIOD_INVALID", "LICENSE_MULTIPLE_SDK_SERVICE", "LICENSE_ILLEGAL", "UID_CONFLICT", "FALLBACK", "FALLBACK_TO_HLS"];
    for (const reason of fatal) { const h = harness(); h.controller.onConnection("DISCONNECTED", "CONNECTED", reason); assert.equal(h.controller.rtcState, "FAILED", reason); }
    const unknown = harness(); unknown.controller.onConnection("DISCONNECTED", "CONNECTED", "FUTURE_REASON"); assert.equal(unknown.controller.rtcState, "FAILED");
    for (const reason of ["NETWORK_ERROR", "TOKEN_EXPIRE"]) {
        const h = harness(); let recoveries = 0; h.controller.recoverExpiredToken = () => { recoveries++; return Promise.resolve(true); };
        h.controller.onConnection("DISCONNECTED", "CONNECTED", reason); assert.equal(recoveries, 1, reason);
    }
});

test("tuple dedupe is scoped to intervening and new operation cycles", async () => {
    const h = harness(); let transitions = 0; h.controller.onState = () => transitions++;
    h.controller.onConnection("RECONNECTING", "CONNECTED");
    h.controller.onConnection("RECONNECTING", "CONNECTED"); assert.equal(transitions, 1);
    h.controller.onConnection("CONNECTED", "RECONNECTING");
    h.controller.onConnection("RECONNECTING", "CONNECTED"); assert.equal(transitions, 3);
    h.controller.startOperation("JOIN", "new-cycle", h.client, true);
    h.controller.onConnection("RECONNECTING", "CONNECTED"); assert.equal(transitions, 4);
});

test("peer closed is expected during leave/token reset and fatal while active", async () => {
    const active = harness(); active.controller.onPeerConnection("closed"); assert.equal(active.controller.rtcState, "FAILED");
    const leaving = harness(); leaving.controller.setRtcState("LEAVING"); leaving.controller.onPeerConnection("closed"); assert.equal(leaving.controller.rtcState, "LEAVING");
    const reset = harness(); reset.controller.tokenResetInProgress = true; reset.controller.setRtcState("JOINING"); reset.controller.onPeerConnection("closed"); assert.equal(reset.controller.rtcState, "JOINING");
});

test("exact exception issue and recovery pairs are categorized without raw fields", async () => {
    const h = harness();
    const groups = [
        [[1001,1002,1003,1005], "AGORA_EXCEPTION_VIDEO"], [[2001,2002,2003,2005], "AGORA_EXCEPTION_AUDIO"],
        [[3001,3002,3003,3005], "AGORA_EXCEPTION_VIDEO_RECOVERED"], [[4001,4002,4003,4005], "AGORA_EXCEPTION_AUDIO_RECOVERED"]
    ];
    for (const [codes, category] of groups) for (const code of codes) {
        h.controller.onException({ code, msg: "raw token", uid: "private" });
        assert.equal(h.diagnostics.at(-1).eventCategory, category); assert.equal(h.diagnostics.at(-1).code, code);
        assert.equal(JSON.stringify(h.diagnostics.at(-1)).includes("raw token"), false);
    }
});

test("owned join confirmation drops stale 200 and never publishes recovery complete", async () => {
    const h = harness(); const response = deferred(); let recoveryPublishes = 0;
    const pending = h.controller.confirmJoin(() => response.promise);
    h.controller.invalidate("SESSION_REPLACED", { replaceSession: true });
    response.resolve({ status: 200, ok: true });
    assert.equal(await pending, null);
    h.controller.activate({ id: "session-b", terminal: false });
    assert.equal(await h.controller.publishRecoveryComplete(() => recoveryPublishes++, "session-a"), null);
    assert.equal(recoveryPublishes, 0);
});

test("token retry schedule is exactly 500 then 1000 plus bounded jitter", async () => {
    const h = harness(); h.controller.random = () => 1; let calls = 0;
    h.controller.fetchToken = async () => { calls++; return { status: 503, ok: false }; };
    const pending = h.controller.fetchWithRetry(h.controller.session);
    await Promise.resolve(); assert.equal([...h.clock.timers.values()].some(timer => timer.at === 700), true);
    h.clock.tick(700); await Promise.resolve(); await Promise.resolve();
    assert.equal([...h.clock.timers.values()].some(timer => timer.at === 1900), true);
    h.clock.tick(1200); assert.equal((await pending).status, 503); assert.equal(calls, 3);
});

test("token HTTP fatal taxonomy never retries", async () => {
    for (const status of [400, 401, 403, 404, 409]) {
        const h = harness(); let calls = 0;
        h.controller.fetchToken = async () => { calls++; return { status, ok: false }; };
        assert.equal((await h.controller.fetchWithRetry(h.controller.session)).status, status);
        assert.equal(calls, 1, String(status));
    }
});

test("network exception is retryable and remains sanitized", async () => {
    const h = harness(); let calls = 0;
    h.controller.fetchToken = async () => { calls++; if (calls < 3) throw new Error("raw credential"); return { status: 200, ok: true, data: { token: "opaque" } }; };
    const pending = h.controller.fetchWithRetry(h.controller.session);
    await Promise.resolve(); h.clock.tick(500); await Promise.resolve(); await Promise.resolve(); h.clock.tick(1000);
    assert.equal((await pending).ok, true); assert.equal(calls, 3); assert.equal(JSON.stringify(h.diagnostics).includes("raw credential"), false);
});

test("ordinary create operation is suppressed after terminal without timer or SDK", async () => {
    const h = harness(); let calls = 0; h.controller.markTerminal(); const timers = h.clock.timers.size;
    assert.equal(await h.controller.ordinary("CREATE_MIC_TRACK", "mic-after-terminal", async () => { calls++; }, () => {}, false), null);
    assert.equal(calls, 0); assert.equal(h.clock.timers.size, timers);
});

test("retry wait cancellation prevents next fetch for leave terminal and generation replacement", async () => {
    for (const reason of ["LEAVE", "TERMINAL", "SESSION_REPLACED"]) {
        const h = harness(); let calls = 0;
        h.controller.fetchToken = async () => { calls++; return { status: 503, ok: false }; };
        const pending = h.controller.renewToken(); await Promise.resolve(); await Promise.resolve();
        assert.equal(h.controller.retryWaits.size, 1, reason);
        h.controller.invalidate(reason, { replaceSession: true });
        assert.equal(await pending, false, reason); assert.equal(calls, 1, reason); assert.equal(h.controller.retryWaits.size, 0, reason);
    }
});

test("cancellation during second retry wait prevents third fetch", async () => {
    const h = harness(); let calls = 0; h.controller.fetchToken = async () => { calls++; return { status: 503, ok: false }; };
    const pending = h.controller.renewToken(); await Promise.resolve(); await Promise.resolve(); h.clock.tick(500);
    await Promise.resolve(); await Promise.resolve(); assert.equal(calls, 2); assert.equal(h.controller.retryWaits.size, 1);
    h.controller.invalidate("SESSION_REPLACED", { replaceSession: true });
    assert.equal(await pending, false); assert.equal(calls, 2); assert.equal(h.controller.retryWaits.size, 0);
});

test("old retry cancellation cannot clear newer retry timer", async () => {
    const h = harness(); let calls = 0; h.controller.fetchToken = async () => { calls++; return { status: 503, ok: false }; };
    const old = h.controller.fetchWithRetry(h.controller.session); await Promise.resolve(); const oldTimer = [...h.controller.retryWaits.values()][0].timer;
    h.controller.invalidate("SESSION_REPLACED", { replaceSession: true }); assert.equal((await old).cancelled, true);
    h.controller.terminalOrLeaveLatch = false;
    const newer = h.controller.fetchWithRetry(h.controller.session); await Promise.resolve(); const newTimer = [...h.controller.retryWaits.values()][0].timer;
    assert.notEqual(oldTimer, newTimer); assert.equal(h.clock.timers.has(newTimer), true);
    h.controller.invalidate("SESSION_REPLACED", { replaceSession: true }); await newer;
});

test("reject exactly at deadline is timeout in both scheduler orders", async () => {
    for (const timerFirst of [false, true]) {
        const h = harness(); const d = deferred();
        const pending = h.controller.ordinary("JOIN", "reject-boundary", () => d.promise, () => assert.fail());
        if (timerFirst) h.clock.tick(DEADLINE_MS); else h.clock.time = DEADLINE_MS;
        d.reject(Object.assign(new Error(), { code: "NETWORK_TIMEOUT" })); await pending;
        if (!timerFirst) h.clock.tick(0);
        assert.equal(h.controller.rtcState, "FAILED"); assert.equal(h.diagnostics.at(-1).stale, true);
    }
});

test("complete AgoraRTCErrorCode taxonomy returns exact category and action", async () => {
    const groups = [
        [["PERMISSION_DENIED"], "DEVICE_PERMISSION", "USER_OR_DEVICE_CHANGE"],
        [["DEVICE_NOT_FOUND","ENUMERATE_DEVICES_FAILED"], "DEVICE_MISSING", "USER_OR_DEVICE_CHANGE"],
        [["NOT_READABLE"], "DEVICE_BUSY_IO", "USER_OR_DEVICE_CHANGE"],
        [["NOT_SUPPORTED","WEB_SECURITY_RESTRICT","CONSTRAINT_NOT_SATISFIED"], "UNSUPPORTED", "USER_OR_DEVICE_CHANGE"],
        [["TOKEN_EXPIRE"], "JOIN_AUTH_TOKEN", "TOKEN_RECOVERY"],
        [["UID_CONFLICT","INVALID_PARAMS","INVALID_UINT_UID_FROM_STRING_UID","UPDATE_TICKET_FAILED","CAN_NOT_GET_GATEWAY_SERVER","VOID_GATEWAY_ADDRESS"], "JOIN_AUTH_TOKEN", "FATAL_CURRENT"],
        [["NETWORK_ERROR","NETWORK_TIMEOUT","NETWORK_RESPONSE_ERROR","API_INVOKE_TIMEOUT","TIMEOUT","WS_ABORT","WS_DISCONNECT","WS_ERR","ICE_FAILED","NO_ICE_CANDIDATE","GATEWAY_P2P_LOST"], "NETWORK_TRANSIENT", "OWNING_BUDGET_ONLY"],
        [["INVALID_LOCAL_TRACK","INVALID_TRACK","TRACK_IS_DISABLED","TRACK_STATE_UNREACHABLE","SENDER_NOT_FOUND","SENDER_REPLACE_FAILED","SUBSCRIBE_FAILED","UNSUBSCRIBE_FAILED","REMOTE_USER_IS_NOT_PUBLISHED","INVALID_REMOTE_USER","CAN_NOT_PUBLISH_MULTIPLE_VIDEO_TRACKS","LOW_STREAM_ENCODING_ERROR","SET_ENCODING_PARAMETER_ERROR"], "MEDIA_TRACK_OPERATION", "MEDIA_POLICY"],
        [["UNEXPECTED_ERROR","UNEXPECTED_RESPONSE","PB_ERROR"], "AGORA_INTERNAL_UNKNOWN", "FATAL_CURRENT"]
    ];
    for (const [codes, category, action] of groups) for (const code of codes) {
        assert.deepEqual(classifyAgoraError({ code }), { code, eventCategory: category, action });
    }
    assert.deepEqual(classifyAgoraError({ code: "FUTURE_RAW" }), { code: "UNKNOWN", eventCategory: "AGORA_INTERNAL_UNKNOWN", action: "FATAL_CURRENT" });
});

test("exception diagnostics suppress duplicate only within generation", async () => {
    const h = harness();
    h.controller.onException({ code: 1001, msg: "raw" }); h.controller.onException({ code: 1001, msg: "other" });
    assert.equal(h.diagnostics.length, 1);
    h.controller.startOperation("JOIN", "exception-cycle", h.client, true);
    h.controller.onException({ code: 1001, msg: "raw" }); assert.equal(h.diagnostics.length, 2);
});

test("quarantined old client cannot run queued API after replacement", async () => {
    const h = harness(); h.controller.isExactObjectCurrent = (type, scope, object) => object === h.controller.client;
    const old = h.client; let calls = 0;
    await h.controller.shared("TOKEN_RENEW", "renew", old, async () => { throw Object.assign(new Error(), { code: "NETWORK_ERROR" }); }, async () => false, () => {}, false);
    assert.notEqual(h.controller.client, old);
    await h.controller.shared("JOIN", "old-client-reuse", old, async () => { calls++; }, async () => true, () => {}, false);
    assert.equal(calls, 0); assert.equal(old.leaveCalls, 1);
});

test("stale publish compensation completes before newer media and preserves it", async () => {
    const h = harness(); const oldGate = deferred(); const oldTrack = { id: "old" }; const newTrack = { id: "new" }; const published = new Set();
    h.client.publish = async tracks => { tracks.forEach(track => published.add(track)); if (tracks[0] === oldTrack) { await oldGate.promise; throw Object.assign(new Error(), { code: "NETWORK_TIMEOUT" }); } };
    h.client.unpublish = async tracks => tracks.forEach(track => published.delete(track));
    const old = h.controller.shared("PUBLISH", "old-publish", h.client, exact => exact.publish([oldTrack]), async exact => { await exact.unpublish([oldTrack]); return true; }, () => {}, false);
    await Promise.resolve(); await Promise.resolve();
    const newer = h.controller.shared("PUBLISH", "new-publish", h.client, exact => exact.publish([newTrack]), async () => true, () => {}, false);
    h.clock.tick(DEADLINE_MS); oldGate.resolve(); await Promise.all([old, newer]);
    assert.deepEqual([...published], [newTrack]);
});

test("owned recovery failure send is suppressed after stale session", async () => {
    const h = harness(); let sends = 0; const gate = deferred();
    const pending = h.controller.ordinary("JOIN", "failing-join", () => gate.promise, () => {});
    h.controller.activate({ id: "session-b", terminal: false }); gate.reject(Object.assign(new Error(), { code: "NETWORK_ERROR" })); await pending;
    assert.equal(await h.controller.publishRecoveryFailure(() => sends++, "session-a"), null); assert.equal(sends, 0);
});

test("recovery side effect claim is one-shot and rejects initial or stale context", async () => {
    const h = harness(); let sends = 0;
    const flow = h.controller.startCallFlow("session-a"); flow.kind = "RECOVERY";
    assert.equal(await h.controller.tryClaimRecoverySideEffect(flow, "RECOVERY_FAILED", () => { sends++; }), undefined);
    assert.equal(await h.controller.tryClaimRecoverySideEffect(flow, "RECOVERY_FAILED", () => { sends++; }), null);
    assert.equal(sends, 1);
    const initial = h.controller.startCallFlow("session-a"); initial.kind = "INITIAL";
    assert.equal(await h.controller.tryClaimRecoverySideEffect(initial, "RECOVERY_FAILED", () => { sends++; }), null);
    assert.equal(sends, 1);
});

test("absent disconnect reason distinguishes token reset from explicit cleanup", async () => {
    const reset = harness(); reset.controller.tokenResetInProgress = true;
    reset.controller.onConnection("DISCONNECTED", "DISCONNECTING"); assert.equal(reset.controller.rtcState, "JOINING");
    const leave = harness(); leave.controller.onConnection("DISCONNECTED", "DISCONNECTING"); assert.equal(leave.controller.rtcState, "IDLE");
});

test("terminal listener race cancels recovery timer and ignores old handlers", async () => {
    const h = harness(); const old = h.client;
    old.emit("connection-state-change", "RECONNECTING", "CONNECTED"); assert.notEqual(h.controller.recoveryTimer, null);
    h.controller.markTerminal(); assert.equal(h.controller.recoveryTimer, null);
    const states = h.states.length; old.emit("connection-state-change", "CONNECTED", "RECONNECTING");
    assert.equal(h.states.length, states);
});

test("classified failure actions drive concrete Slice1 state policy", async () => {
    {
        const h = harness(); h.controller.consumeFailurePolicy({ operationType: "CREATE_CAMERA_TRACK" }, { code: "NOT_READABLE" });
        assert.equal(h.controller.cameraState, "DEGRADED_AUDIO_ONLY"); assert.equal(h.controller.rtcState, "IDLE");
    }
    {
        const h = harness(); h.controller.consumeFailurePolicy({ operationType: "CREATE_MIC_TRACK" }, { code: "PERMISSION_DENIED" });
        assert.equal(h.controller.rtcState, "FAILED");
    }
    for (const code of ["NETWORK_TIMEOUT", "UNEXPECTED_ERROR", "FUTURE_RAW"]) {
        const h = harness(); h.controller.consumeFailurePolicy({ operationType: "JOIN" }, { code }); assert.equal(h.controller.rtcState, "FAILED", code);
    }
    {
        const h = harness(); h.controller.consumeFailurePolicy({ operationType: "PUBLISH" }, { code: "INVALID_TRACK" });
        assert.equal(h.controller.mediaReconnectState, "FAILED");
    }
    {
        const h = harness(); let recoveries = 0; h.controller.recoverExpiredToken = () => { recoveries++; return Promise.resolve(true); };
        h.controller.consumeFailurePolicy({ operationType: "JOIN" }, { code: "TOKEN_EXPIRE" });
        assert.equal(h.controller.tokenState, "EXPIRED"); assert.equal(recoveries, 1);
    }
    {
        const h = harness(); h.controller.consumeFailurePolicy({ operationType: "TOKEN_FETCH" }, { code: "TERMINAL" });
        assert.equal(h.controller.terminalOrLeaveLatch, true);
    }
});

function adaptiveHarness() {
    const clock = new FakeClock(); const calls = []; const states = []; const notices = []; const volumes = [];
    const client = {
        async enableDualStream() { calls.push("dual"); },
        async setRemoteVideoStreamType(uid, type) { calls.push(`stream:${uid}:${type}`); },
        async setStreamFallbackOption(uid, option) { calls.push(`fallback:${uid}:${option}`); },
        async enableAudioVolumeIndicator() { calls.push("volume"); }
    };
    const adaptive = new AdaptiveMediaController({ client, clock, getCurrentUid: () => "peer", onState: value => states.push(value), onNotice: value => notices.push(value), onVolume: value => volumes.push(value) });
    return { clock, calls, client, states, notices, volumes, adaptive };
}

test("Slice 3 dual stream configures high and audio-only fallback after local video publish", async () => {
    const h = adaptiveHarness(); await h.adaptive.afterLocalVideoPublished(); await h.adaptive.configureRemote("peer");
    assert.deepEqual(h.calls, ["dual", "stream:peer:0", "fallback:peer:2"]);
});

test("Slice 3 network buckets are consecutive, per-uid and hysteretic", async () => {
    const h = adaptiveHarness(); h.adaptive.onRtcState("CONNECTED", "IDLE");
    [4, 0, 4, 6, 4].forEach(downlinkNetworkQuality => h.adaptive.onNetworkQuality({ uid: "peer", downlinkNetworkQuality })); await Promise.resolve();
    assert.equal(h.calls.filter(call => call === "stream:peer:1").length, 1);
    h.clock.tick(10000); [1, 2, 1, 2, 1].forEach(downlinkNetworkQuality => h.adaptive.onNetworkQuality({ uid: "peer", downlinkNetworkQuality })); await Promise.resolve();
    assert.equal(h.calls.filter(call => call === "stream:peer:0").length, 1);
    h.adaptive.onNetworkQuality({ uid: "peer", downlinkNetworkQuality: 3 }); assert.equal(h.adaptive.state("peer").goodConsecutive, 0);
});

test("Slice 3 reconnect fallback and reset do not oscillate", async () => {
    const h = adaptiveHarness(); h.adaptive.state("peer"); h.adaptive.onRtcState("RECONNECTING", "IDLE"); await Promise.resolve(); h.adaptive.onRtcState("RECONNECTING", "IDLE"); await Promise.resolve();
    assert.equal(h.calls.filter(call => call === "stream:peer:1").length, 1);
    h.adaptive.onStreamFallback("peer", "fallback"); assert.equal(h.states.at(-1).fallbackState, "AUDIO_ONLY"); h.adaptive.onStreamFallback("peer", "recover"); assert.equal(h.states.at(-1).fallbackState, "HIGH");
    h.adaptive.reset(); assert.equal(h.adaptive.states.size, 0);
});

test("Slice 3 SDK failures disable adaptation and volume is throttled in memory", async () => {
    const h = adaptiveHarness(); h.client.enableDualStream = async () => { throw new Error("sdk"); }; await h.adaptive.afterLocalVideoPublished();
    assert.equal(h.adaptive.adaptationEnabled, false); assert.deepEqual(h.notices, ["ADAPTATION_UNAVAILABLE"]);
    const volume = adaptiveHarness(); await volume.adaptive.enableVolume(); volume.adaptive.onVolumeIndicator([{ uid: "self", level: 61 }, { uid: "peer", level: 70 }]); volume.adaptive.onVolumeIndicator([{ uid: "peer", level: 70 }]); volume.clock.tick(250); volume.adaptive.onVolumeIndicator([{ uid: "peer", level: 10 }]);
    assert.equal(volume.calls.includes("volume"), true); assert.equal(volume.volumes.length, 2); assert.deepEqual(volume.volumes[0].map(item => item.uid), ["self", "peer"]);
});

test("Slice 3 late adaptive callback after session replacement cannot mutate session B", async () => {
    const h = adaptiveHarness(); h.adaptive.startSession("A"); const contextA = h.adaptive.context();
    let resolve; h.client.setRemoteVideoStreamType = () => new Promise(done => { resolve = done; });
    const pending = h.adaptive.requestStream("peer", 1, false, contextA); await Promise.resolve();
    h.adaptive.startSession("B"); const statesAtB = h.states.length; h.adaptive.onStreamFallback("peer", "fallback", contextA); resolve(); await pending;
    assert.equal(h.adaptive.sessionId, "B"); assert.equal(h.adaptive.states.size, 0); assert.equal(h.states.length, statesAtB); assert.equal(h.calls.filter(call => call === "stream:peer:1").length, 0);
});

test("Slice 3 recovery reset renders UNKNOWN and old context cannot resurrect counters or reconnect state", () => {
    const h = adaptiveHarness(); h.adaptive.startSession("A"); const contextA = h.adaptive.context(); h.adaptive.state("peer");
    h.adaptive.onRtcState("RECONNECTING", "RECONNECTING", contextA); h.adaptive.onNetworkQuality({ uid: "peer", downlinkNetworkQuality: 6 }, contextA);
    h.adaptive.startSession("B"); const statesAtReset = h.states.length; h.adaptive.onNetworkQuality({ uid: "peer", downlinkNetworkQuality: 6 }, contextA); h.adaptive.onRtcState("RECONNECTING", "RECONNECTING", contextA);
    assert.equal(h.adaptive.rtcState, "IDLE"); assert.equal(h.adaptive.mediaReconnectActive, false); assert.equal(h.adaptive.reconnectDegraded, false); assert.equal(h.adaptive.states.size, 0); assert.equal(h.states.length, statesAtReset); assert.equal(h.states.at(-1).networkHealth, "UNKNOWN");
});

test("Slice 3 renderer maps every network matrix row once and suppresses duplicate non-transitions", () => {
    const h = adaptiveHarness(); h.adaptive.startSession("A"); const context = h.adaptive.context(); h.states.length = 0;
    const sample = downlinkNetworkQuality => h.adaptive.onNetworkQuality({ uid: "peer", downlinkNetworkQuality }, context);
    sample(0); sample(0); sample(1); sample(2); sample(3); sample(3); sample(4); sample(6);
    h.adaptive.onRtcState("RECONNECTING", "IDLE", context); h.adaptive.onRtcState("RECONNECTING", "IDLE", context); h.adaptive.onRtcState("CONNECTED", "IDLE", context);
    assert.deepEqual(h.states.map(state => state.networkHealth), ["UNKNOWN", "GOOD", "UNSTABLE", "POOR", "RECONNECTING", "UNKNOWN"]);
    assert.equal(h.states.every(state => state.uid === "peer"), true);
    assert.equal(h.adaptive.state("peer").goodConsecutive, 0); assert.equal(h.adaptive.state("peer").degradedConsecutive, 0); assert.equal(h.adaptive.reconnectDegraded, false);
});

(async () => {
    let passed = 0;
    let total = 0;
    const report = async (name, fn) => {
        total++;
        try { await fn(); passed++; process.stdout.write(`PASS ${name}\n`); }
        catch (error) { process.stderr.write(`FAIL ${name}: ${error.message}\n`); process.exitCode = 1; }
    };
    for (const item of tests) await report(item.name, item.fn);
    await require("./video_call_flow.test.cjs")(report);
    process.stdout.write(`RESULT tests=${total} passed=${passed} failed=${total - passed} skipped=0\n`);
})();
