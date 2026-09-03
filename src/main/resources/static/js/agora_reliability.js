(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    else root.AgoraReliability = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    const DEADLINE_MS = 15000;
    const DEVICE_CODES = new Set([
        "PERMISSION_DENIED", "DEVICE_NOT_FOUND", "ENUMERATE_DEVICES_FAILED", "NOT_READABLE",
        "NOT_SUPPORTED", "WEB_SECURITY_RESTRICT", "CONSTRAINT_NOT_SATISFIED"
    ]);
    const JOIN_AUTH_CODES = new Set(["TOKEN_EXPIRE", "UID_CONFLICT", "INVALID_PARAMS", "INVALID_UINT_UID_FROM_STRING_UID", "UPDATE_TICKET_FAILED", "CAN_NOT_GET_GATEWAY_SERVER", "VOID_GATEWAY_ADDRESS", "UNAUTHORIZED", "SESSION_NOT_FOUND", "TERMINAL"]);
    const NETWORK_CODES = new Set(["NETWORK_ERROR", "NETWORK_TIMEOUT", "NETWORK_RESPONSE_ERROR", "API_INVOKE_TIMEOUT", "TIMEOUT", "WS_ABORT", "WS_DISCONNECT", "WS_ERR", "ICE_FAILED", "NO_ICE_CANDIDATE", "GATEWAY_P2P_LOST"]);
    const MEDIA_CODES = new Set(["INVALID_LOCAL_TRACK", "INVALID_TRACK", "TRACK_IS_DISABLED", "TRACK_STATE_UNREACHABLE", "SENDER_NOT_FOUND", "SENDER_REPLACE_FAILED", "SUBSCRIBE_FAILED", "UNSUBSCRIBE_FAILED", "REMOTE_USER_IS_NOT_PUBLISHED", "INVALID_REMOTE_USER", "CAN_NOT_PUBLISH_MULTIPLE_VIDEO_TRACKS", "LOW_STREAM_ENCODING_ERROR", "SET_ENCODING_PARAMETER_ERROR"]);
    const INTERNAL_CODES = new Set(["UNEXPECTED_ERROR", "UNEXPECTED_RESPONSE", "PB_ERROR"]);

    function classifyAgoraError(error) {
        const code = error && (error.code || error.name);
        if (typeof code !== "string") return { code: "UNKNOWN", eventCategory: "AGORA_INTERNAL_UNKNOWN", action: "FATAL_CURRENT" };
        if (DEVICE_CODES.has(code)) {
            const eventCategory = code === "PERMISSION_DENIED" ? "DEVICE_PERMISSION"
                : (code === "DEVICE_NOT_FOUND" || code === "ENUMERATE_DEVICES_FAILED") ? "DEVICE_MISSING"
                    : code === "NOT_READABLE" ? "DEVICE_BUSY_IO" : "UNSUPPORTED";
            return { code, eventCategory, action: "USER_OR_DEVICE_CHANGE" };
        }
        if (JOIN_AUTH_CODES.has(code)) return { code, eventCategory: "JOIN_AUTH_TOKEN", action: code === "TOKEN_EXPIRE" ? "TOKEN_RECOVERY" : code === "TERMINAL" ? "TERMINAL" : "FATAL_CURRENT" };
        if (NETWORK_CODES.has(code)) return { code, eventCategory: "NETWORK_TRANSIENT", action: "OWNING_BUDGET_ONLY" };
        if (MEDIA_CODES.has(code)) return { code, eventCategory: "MEDIA_TRACK_OPERATION", action: "MEDIA_POLICY" };
        if (INTERNAL_CODES.has(code)) return { code, eventCategory: "AGORA_INTERNAL_UNKNOWN", action: "FATAL_CURRENT" };
        return { code: "UNKNOWN", eventCategory: "AGORA_INTERNAL_UNKNOWN", action: "FATAL_CURRENT" };
    }

    class AgoraReliabilityController {
        constructor(options) {
            this.clock = options.clock || { now: () => performance.now(), setTimeout, clearTimeout };
            this.fetchToken = options.fetchToken;
            this.createClient = options.createClient;
            this.appId = options.appId;
            this.random = options.random || Math.random;
            this.onState = options.onState || (() => {});
            this.onDiagnostic = options.onDiagnostic || (() => {});
            this.onClientReplaced = options.onClientReplaced || (() => {});
            this.getRetainedTracks = options.getRetainedTracks || (() => []);
            this.postRejoinSetup = options.postRejoinSetup || (() => {});
            this.isAuthorized = options.isAuthorized || (() => true);
            this.isExactObjectCurrent = options.isExactObjectCurrent || (() => true);
            this.client = options.client;
            this.session = null;
            this.mediaGeneration = 0;
            this.cleanupGeneration = 0;
            this.clientGeneration = 1;
            this.sequence = 0;
            this.objectIds = new WeakMap();
            this.currentOwners = new Map();
            this.cleanupDone = new WeakSet();
            this.leases = new Map();
            this.settlementMarkers = new Map();
            this.latestIntents = new Map();
            this.intentSequence = 0;
            this.terminalOrLeaveLatch = false;
            this.listeners = [];
            this.customHandlers = [];
            this.listenerClient = null;
            this.renewFlight = null;
            this.recoveryFlight = null;
            this.recoveryTimer = null;
            this.rtcState = "IDLE";
            this.mediaReconnectState = "IDLE";
            this.tokenState = "IDLE";
            this.cameraState = "AVAILABLE";
            this.lastConnectionEvent = null;
            this.mediaReconnectUids = new Set();
            this.mediaReconnectTimers = new Map();
            this.tokenResetInProgress = false;
            this.exceptionIssues = new Set();
            this.exceptionDiagnostics = new Set();
            this.retryWaits = new Map();
            this.retryWaitSequence = 0;
            this.flowSequence = 0;
            this.currentCallFlow = null;
            this.recoverySideEffectClaims = new Set();
        }

        activate(session) {
            if (this.session && this.session.id === session.id && !this.terminalOrLeaveLatch) {
                this.bindClient(this.client);
                return;
            }
            this.invalidate("SESSION_REPLACED", { replaceSession: true });
            this.terminalOrLeaveLatch = false;
            this.session = session;
            this.lastConnectionEvent = null;
            this.exceptionDiagnostics.clear();
            this.bindClient(this.client);
        }

        startOperation(type, scope, clientRef, startsNewGeneration) {
            if (startsNewGeneration) { this.mediaGeneration++; this.lastConnectionEvent = null; this.exceptionDiagnostics.clear(); }
            const old = this.currentOwners.get(scope);
            if (old && old.state === "CURRENT") this.revoke(old, "REVOKED");
            const startedAt = this.clock.now();
            const op = {
                operationId: `media-${++this.sequence}`,
                operationType: type,
                scope,
                mediaGeneration: this.mediaGeneration,
                cleanupGeneration: this.cleanupGeneration,
                clientGeneration: this.clientGeneration,
                sessionId: this.session && this.session.id,
                clientRef,
                startedAt,
                deadlineAt: startedAt + DEADLINE_MS,
                state: "CURRENT",
                timer: null
            };
            this.currentOwners.set(scope, op);
            op.timer = this.clock.setTimeout(() => this.expire(op), DEADLINE_MS);
            return op;
        }

        isCurrentOwner(op) {
            return !!op && op.state === "CURRENT"
                && this.currentOwners.get(op.scope) === op
                && op.mediaGeneration === this.mediaGeneration
                && op.cleanupGeneration === this.cleanupGeneration
                && op.clientGeneration === this.clientGeneration
                && this.session && op.sessionId === this.session.id
                && !this.session.terminal && !this.terminalOrLeaveLatch
                && this.isAuthorized(this.session)
                && this.clock.now() < op.deadlineAt;
        }

        isCurrentOwnerForCommit(op) {
            return !!op && op.state === "SUCCEEDED" && op.mediaGeneration === this.mediaGeneration
                && op.cleanupGeneration === this.cleanupGeneration && op.clientGeneration === this.clientGeneration
                && this.session && op.sessionId === this.session.id && !this.terminalOrLeaveLatch
                && this.isAuthorized(this.session);
        }

        startCallFlow(sessionId) {
            if (!this.session || this.session.id !== sessionId || this.terminalOrLeaveLatch || !this.isAuthorized(this.session)) return null;
            if (this.currentCallFlow) this.currentCallFlow.revoked = true;
            Array.from(this.currentOwners.values()).forEach(op => this.revoke(op, "REVOKED"));
            this.mediaGeneration++;
            this.lastConnectionEvent = null;
            this.exceptionDiagnostics.clear();
            const flow = {
                flowId: `call-flow-${++this.flowSequence}`, sessionId,
                cleanupGeneration: this.cleanupGeneration, mediaGeneration: this.mediaGeneration,
                clientGeneration: this.clientGeneration, clientRef: this.client, revoked: false
            };
            this.currentCallFlow = flow;
            return flow;
        }

        isCurrentCallFlow(flow) {
            return !!flow && !flow.revoked && this.currentCallFlow === flow && this.session
                && this.session.id === flow.sessionId && !this.session.terminal && !this.terminalOrLeaveLatch
                && this.cleanupGeneration === flow.cleanupGeneration && this.mediaGeneration === flow.mediaGeneration
                && this.clientGeneration === flow.clientGeneration && this.client === flow.clientRef
                && this.isAuthorized(this.session);
        }

        revokeCallFlow() {
            if (this.currentCallFlow) this.currentCallFlow.revoked = true;
            this.currentCallFlow = null;
        }

        clearOwnedTimer(op) {
            if (!op || op.timer === null) return;
            this.clock.clearTimeout(op.timer);
            op.timer = null;
        }

        revoke(op, state) {
            if (!op || op.state !== "CURRENT") return false;
            op.state = state;
            if (this.currentOwners.get(op.scope) === op) this.currentOwners.delete(op.scope);
            this.clearOwnedTimer(op);
            return true;
        }

        expire(op) {
            if (this.clock.now() < op.deadlineAt) return false;
            if (this.revoke(op, "TIMED_OUT")) {
                this.setRtcState("FAILED");
                return true;
            }
            return op.state === "TIMED_OUT";
        }

        settle(op, success, error, preserveFailureState) {
            this.expire(op);
            if (!this.isCurrentOwner(op)) {
                this.diagnostic(op, error, true);
                return false;
            }
            op.state = success ? "SUCCEEDED" : "FAILED";
            if (this.currentOwners.get(op.scope) === op) this.currentOwners.delete(op.scope);
            this.clearOwnedTimer(op);
            if (!success && (op.operationType === "TOKEN_FETCH" || op.operationType === "TOKEN_RENEW")) {
                this.tokenState = "DEGRADED";
                this.onState(this.snapshot());
            } else if (!success && !preserveFailureState) this.consumeFailurePolicy(op, error);
            return true;
        }

        consumeFailurePolicy(op, error) {
            const policy = classifyAgoraError(error);
            this.lastFailurePolicy = policy;
            if (policy.action === "TOKEN_RECOVERY") {
                this.tokenState = "EXPIRED";
                this.setRtcState("DISCONNECTED");
                void this.recoverExpiredToken();
            } else if (policy.action === "USER_OR_DEVICE_CHANGE") {
                if (op.operationType === "CREATE_CAMERA_TRACK") {
                    this.cameraState = "DEGRADED_AUDIO_ONLY";
                    this.onState(this.snapshot());
                } else this.setRtcState("FAILED");
            } else if (policy.action === "MEDIA_POLICY") {
                this.mediaReconnectState = "FAILED";
                this.onState(this.snapshot());
            } else if (policy.action === "TERMINAL") {
                this.markTerminal();
            } else {
                this.setRtcState("FAILED");
            }
            return policy;
        }

        diagnostic(op, error, stale) {
            const classified = classifyAgoraError(error);
            this.onDiagnostic({
                operationType: op ? op.operationType : "EVENT",
                code: classified.code,
                eventCategory: classified.eventCategory,
                action: classified.action,
                stale: !!stale,
                operationId: op && op.operationId
            });
        }

        cleanupResource(op, resource) {
            if (!resource || this.cleanupDone.has(resource)) return;
            if (resource.__ownerOperationId && resource.__ownerOperationId !== op.operationId) return;
            this.cleanupDone.add(resource);
            try { if (typeof resource.stop === "function") resource.stop(); } catch (e) { this.diagnostic(op, e, true); }
            try { if (typeof resource.close === "function") resource.close(); } catch (e) { this.diagnostic(op, e, true); }
        }

        async ordinary(type, scope, task, commit, startsNewGeneration, failurePolicy) {
            if (!this.session || this.terminalOrLeaveLatch || this.session.terminal || !this.isAuthorized(this.session)) return null;
            const op = this.startOperation(type, scope, this.client, !!startsNewGeneration);
            try {
                if (!this.isCurrentOwner(op)) { this.revoke(op, "CANCELLED"); return null; }
                const result = await task(op);
                if (result && typeof result === "object" && !result.__ownerOperationId) result.__ownerOperationId = op.operationId;
                if (!this.settle(op, true)) {
                    this.cleanupResource(op, result);
                    return null;
                }
                commit(result, op);
                return result;
            } catch (error) {
                const preserveFailureState = failurePolicy && failurePolicy.preserveFailureState
                    && typeof failurePolicy.isCurrent === "function" && failurePolicy.isCurrent(op, error)
                    && classifyAgoraError(error).action !== "TERMINAL";
                this.settle(op, false, error, preserveFailureState);
                return null;
            }
        }

        withLease(key, intent) {
            const previous = this.leases.get(key) || Promise.resolve();
            const next = previous.catch(() => {}).then(intent);
            const tracked = next.finally(() => {
                if (this.leases.get(key) === tracked) this.leases.delete(key);
            });
            this.leases.set(key, tracked);
            return next;
        }

        leaseKey(object) { return `sdk-object:${this.objectId(object)}`; }

        queueIntent(type, scope, exactObject, run, priority) {
            const previous = this.latestIntents.get(scope);
            if (previous) previous.tombstone = true;
            const intent = {
                intentId: `intent-${++this.intentSequence}`, type, scope, exactObject,
                sessionId: this.session && this.session.id,
                cleanupGeneration: this.cleanupGeneration,
                mediaGeneration: this.mediaGeneration,
                clientGeneration: this.clientGeneration,
                tombstone: false,
                priority: priority || "NORMAL"
            };
            this.latestIntents.set(scope, intent);
            return this.withLease(this.leaseKey(exactObject), async () => {
                if (intent.tombstone || this.latestIntents.get(scope) !== intent) return null;
                if (intent.priority !== "TERMINAL" && (this.terminalOrLeaveLatch || !this.session
                    || this.session.id !== intent.sessionId || this.cleanupGeneration !== intent.cleanupGeneration
                    || this.mediaGeneration !== intent.mediaGeneration || this.clientGeneration !== intent.clientGeneration
                    || this.session.terminal || !this.isAuthorized(this.session)
                    || !this.isExactObjectCurrent(type, scope, exactObject))) {
                    intent.tombstone = true;
                    return null;
                }
                return run(intent);
            }).finally(() => {
                if (this.latestIntents.get(scope) === intent) this.latestIntents.delete(scope);
            });
        }

        registerClientHandler(event, handler) {
            if (!this.customHandlers.some(item => item.event === event && item.handler === handler)) this.customHandlers.push({ event, handler });
            const client = this.listenerClient;
            if (!client) return;
            const generation = this.clientGeneration;
            const guarded = (...args) => {
                if (this.listenerClient === client && generation === this.clientGeneration && !this.terminalOrLeaveLatch) handler(...args);
            };
            client.on(event, guarded);
            this.listeners.push({ client, event, guarded });
        }

        async shared(type, scope, exactObject, sdkCall, reconcile, commit, startsNewGeneration, onUnsafe, failurePolicy) {
            return this.queueIntent(type, scope, exactObject, async intent => {
                const op = this.startOperation(type, scope, exactObject, !!startsNewGeneration);
                let result;
                let accepted = false;
                let error = null;
                try {
                    result = await sdkCall(exactObject, op);
                    accepted = this.settle(op, true);
                } catch (caught) {
                    error = caught;
                    const preserveFailureState = failurePolicy && failurePolicy.preserveFailureState
                        && typeof failurePolicy.isCurrent === "function" && failurePolicy.isCurrent(exactObject, op, caught)
                        && classifyAgoraError(caught).action !== "TERMINAL";
                    this.settle(op, false, caught, preserveFailureState);
                }
                let safe = false;
                try { safe = await reconcile(exactObject, op, accepted, error); }
                catch (caught) { this.diagnostic(op, caught, !accepted); }
                const stillCurrent = accepted && this.session && !this.terminalOrLeaveLatch
                    && op.sessionId === this.session.id && op.cleanupGeneration === this.cleanupGeneration
                    && op.mediaGeneration === this.mediaGeneration && op.clientGeneration === this.clientGeneration
                    && this.isAuthorized(this.session) && this.latestIntents.get(scope) === intent
                    && exactObject === intent.exactObject && this.isExactObjectCurrent(type, scope, exactObject);
                const preserveRejectedCurrent = !accepted && error && failurePolicy
                    && failurePolicy.mode === "PRESERVE_CURRENT_ON_REJECT"
                    && typeof failurePolicy.isCurrent === "function"
                    && failurePolicy.isCurrent(exactObject, op, error)
                    && classifyAgoraError(error).action !== "TERMINAL";
                if (!safe) {
                    if (preserveRejectedCurrent) {
                        this.recordMarker(op, "REJECTED_CURRENT_PRESERVED");
                        return null;
                    }
                    if (onUnsafe) await onUnsafe(exactObject, op, result, error);
                    else await this.retireUnderLease(exactObject, op);
                    this.recordMarker(op, "QUARANTINED_RETIRED");
                    if (!onUnsafe && exactObject === this.client && !this.terminalOrLeaveLatch) this.replaceClient(exactObject);
                    return null;
                }
                this.recordMarker(op, "SAFE_VERIFIED");
                if (stillCurrent && this.settlementMarkers.get(op.operationId) === "SAFE_VERIFIED") {
                    commit(result, op);
                    return result;
                }
                return null;
            });
        }

        recordMarker(op, marker) {
            const existing = this.settlementMarkers.get(op.operationId);
            if (existing) return existing === marker;
            this.settlementMarkers.set(op.operationId, marker);
            return true;
        }

        objectId(object) {
            if (!this.objectIds.has(object)) this.objectIds.set(object, ++this.sequence);
            return this.objectIds.get(object);
        }

        captureDeviceContext(track) {
            return {
                sessionId: this.session && this.session.id,
                mediaGeneration: this.mediaGeneration,
                cleanupGeneration: this.cleanupGeneration,
                clientGeneration: this.clientGeneration,
                clientRef: this.client,
                trackRef: track
            };
        }

        isDeviceContextCurrent(context, track) {
            return !!context && !!this.session && !this.session.terminal && !this.terminalOrLeaveLatch
                && context.sessionId === this.session.id && context.mediaGeneration === this.mediaGeneration
                && context.cleanupGeneration === this.cleanupGeneration && context.clientGeneration === this.clientGeneration
                && context.clientRef === this.client && context.trackRef === track && this.isAuthorized(this.session);
        }

        replaceClient(expectedOldClient) {
            if (this.client !== expectedOldClient) return this.client;
            this.unbindClient();
            this.revokeCallFlow();
            this.clientGeneration++;
            this.client = this.createClient();
            this.onClientReplaced(this.client);
            if (this.session && !this.terminalOrLeaveLatch) this.bindClient(this.client);
            return this.client;
        }

        async retireUnderLease(object, op) {
            if (!object || this.cleanupDone.has(object)) return;
            this.cleanupDone.add(object);
            if (typeof object.leave === "function" && object.connectionState !== "DISCONNECTED") {
                try { await object.leave(); } catch (error) { this.diagnostic(op, error, true); }
            }
            if (typeof object.stop === "function") { try { object.stop(); } catch (error) { this.diagnostic(op, error, true); } }
            if (typeof object.close === "function") { try { object.close(); } catch (error) { this.diagnostic(op, error, true); } }
        }

        retireClientOrResource(object, op) {
            return this.queueIntent("CLEANUP_LEAVE", `cleanup:${this.objectId(object)}`, object,
                () => this.retireUnderLease(object, op), "TERMINAL");
        }

        renewToken() {
            if (this.renewFlight) return this.renewFlight;
            const client = this.client;
            const session = this.session;
            if (!client || !session || session.terminal || this.terminalOrLeaveLatch) return Promise.resolve(false);
            this.renewFlight = this.ordinary("TOKEN_FETCH", `token-fetch:${session.id}`, async op => {
                const response = await this.fetchWithRetry(session, op);
                if (response.status === 409) {
                    this.markTerminal();
                    await this.cleanupCall([], "TERMINAL");
                    const error = new Error(); error.code = "TERMINAL"; throw error;
                }
                if (response.status === 401 || response.status === 403 || response.status === 404) {
                    await this.cleanupCall([], "AUTH_FATAL");
                    const error = new Error(); error.code = response.status === 404 ? "SESSION_NOT_FOUND" : "UNAUTHORIZED"; throw error;
                }
                if (!response.ok) { const error = new Error(); error.code = "UNAUTHORIZED"; throw error; }
                if (!this.validTokenIdentity(session, response.data)) { const error = new Error(); error.code = "UNAUTHORIZED"; throw error; }
                return response.data;
            }, () => {}, false).then(tokenData => {
                if (!tokenData) return false;
                return this.shared("TOKEN_RENEW", `token-renew:${session.id}`, client,
                    exact => exact.renewToken(tokenData.token),
                    async (exact, op, accepted) => accepted && exact === this.client && op.clientGeneration === this.clientGeneration,
                    () => {}, false).then(result => result !== null && client === this.client && !this.terminalOrLeaveLatch);
            }).finally(() => { this.renewFlight = null; });
            return this.renewFlight;
        }

        retryDelay(ms, session, op) {
            const waitId = `retry-wait-${++this.retryWaitSequence}`;
            const captured = {
                sessionId: session.id, mediaGeneration: this.mediaGeneration,
                cleanupGeneration: this.cleanupGeneration, clientGeneration: this.clientGeneration,
                operationId: op && op.operationId
            };
            return new Promise(resolve => {
                const finish = value => {
                    const current = this.retryWaits.get(waitId);
                    if (!current) return;
                    this.retryWaits.delete(waitId);
                    current.resolve = null;
                    resolve(value);
                };
                const timer = this.clock.setTimeout(() => {
                    const valid = !this.terminalOrLeaveLatch && this.session && !this.session.terminal
                        && this.session.id === captured.sessionId && this.mediaGeneration === captured.mediaGeneration
                        && this.cleanupGeneration === captured.cleanupGeneration && this.clientGeneration === captured.clientGeneration
                        && this.isAuthorized(this.session) && (!op || this.isCurrentOwner(op));
                    finish(valid);
                }, ms);
                this.retryWaits.set(waitId, { timer, resolve: finish, captured });
            });
        }

        cancelRetryWaits() {
            for (const [waitId, wait] of [...this.retryWaits]) {
                this.clock.clearTimeout(wait.timer);
                if (wait.resolve) wait.resolve(false);
                this.retryWaits.delete(waitId);
            }
        }

        async fetchWithRetry(session, op) {
            let response;
            for (let attempt = 1; attempt <= 3; attempt++) {
                try { response = await this.fetchToken(session.id); }
                catch (_) { response = { status: 0, ok: false }; }
                if (response.status === 409 || response.status === 401 || response.status === 403 || response.ok) return response;
                if (attempt < 3 && (response.status === 0 || response.status === 408 || response.status === 429 || response.status >= 500)) {
                    const base = attempt === 1 ? 500 : 1000;
                    const jitter = Math.min(200, Math.max(0, Math.floor(this.random() * 201)));
                    if (!await this.retryDelay(base + jitter, session, op)) return { status: 499, ok: false, cancelled: true };
                    continue;
                }
                return response;
            }
            return response;
        }

        validTokenIdentity(session, tokenData) {
            if (!tokenData || typeof tokenData.token !== "string") return false;
            if (session.channelName && tokenData.channelName !== session.channelName) return false;
            if (session.uid !== undefined && String(tokenData.uid) !== String(session.uid)) return false;
            return true;
        }

        fetchInitialToken() {
            const session = this.session;
            if (!session || this.terminalOrLeaveLatch) return Promise.resolve(null);
            return this.ordinary("TOKEN_FETCH", `token-fetch:${session.id}`, async op => {
                const response = await this.fetchWithRetry(session, op);
                if (response.status === 409) { this.markTerminal(); await this.cleanupCall([], "TERMINAL"); return null; }
                if (response.status === 401 || response.status === 403 || response.status === 404) { await this.cleanupCall([], "AUTH_FATAL"); return null; }
                if (!response.ok || !this.validTokenIdentity(session, response.data)) return null;
                return response.data;
            }, tokenData => {
                if (session.uid === undefined) session.uid = tokenData.uid;
                if (!session.channelName) session.channelName = tokenData.channelName;
            }, false);
        }

        confirmJoin(confirmRequest) {
            return this.ordinary("JOIN_CONFIRM", `join-confirm:${this.session && this.session.id}`, async () => {
                const response = await confirmRequest();
                if (response.status === 409) { this.markTerminal(); await this.cleanupCall([], "TERMINAL"); return null; }
                if (!response.ok) { const error = new Error(); error.code = response.status === 401 || response.status === 403 ? "UNAUTHORIZED" : response.status === 404 ? "SESSION_NOT_FOUND" : "UNKNOWN"; throw error; }
                return true;
            }, () => {}, false);
        }

        publishRecoveryComplete(publish, expectedSessionId) {
            if (!this.session || (expectedSessionId && this.session.id !== expectedSessionId)) return Promise.resolve(null);
            return this.ordinary("RECOVERY_COMPLETE", `recovery-complete:${this.session && this.session.id}`,
                async () => { publish(); return true; }, () => {}, false);
        }

        publishRecoveryFailure(publish, expectedSessionId) {
            if (!this.session || (expectedSessionId && this.session.id !== expectedSessionId)) return Promise.resolve(null);
            return this.ordinary("RECOVERY_FAILED", `recovery-failed:${this.session.id}`,
                async () => { publish(); return true; }, () => {}, false);
        }

        tryClaimRecoverySideEffect(context, effectType, send) {
            if (!context || context.kind !== "RECOVERY" || !this.session || this.terminalOrLeaveLatch
                || this.session.terminal || this.session.id !== context.sessionId
                || !this.isAuthorized(this.session) || this.currentCallFlow !== context
                || this.mediaGeneration !== context.mediaGeneration
                || this.clientGeneration !== context.clientGeneration
                || this.cleanupGeneration !== context.cleanupGeneration) return Promise.resolve(null);
            const key = `${effectType}:${context.flowId}:${context.sessionId}:${context.mediaGeneration}:${context.clientGeneration}`;
            if (this.recoverySideEffectClaims.has(key)) return Promise.resolve(null);
            this.recoverySideEffectClaims.add(key);
            try {
                return Promise.resolve(send());
            } catch (error) {
                this.diagnostic(null, error, false);
                return Promise.resolve(null);
            }
        }

        recoverExpiredToken() {
            if (this.recoveryFlight) return this.recoveryFlight;
            this.setRtcState("RECONNECTING");
            const session = this.session;
            const client = this.client;
            this.recoveryFlight = this.ordinary("TOKEN_FETCH", `token-recovery-fetch:${session.id}`, async op => {
                const response = await this.fetchWithRetry(session, op);
                if (response.status === 409) { this.markTerminal(); await this.cleanupCall([], "TERMINAL"); return null; }
                if (response.status === 401 || response.status === 403 || response.status === 404) { await this.cleanupCall([], "AUTH_FATAL"); return null; }
                if (!response.ok || !this.validTokenIdentity(session, response.data)) return null;
                return response.data;
            }, () => {}, true).then(tokenData => {
                if (!tokenData || this.terminalOrLeaveLatch) return false;
                return this.shared("TRANSPORT_RECOVERY", "transport", client, async exact => {
                    this.tokenResetInProgress = true;
                    if (exact.connectionState !== "DISCONNECTED") await exact.leave();
                    const uid = await exact.join(this.appId, tokenData.channelName, tokenData.token, tokenData.uid);
                    const tracks = this.getRetainedTracks().filter(track => track && !(typeof track.isPlaying === "boolean" && !track.isPlaying));
                    if (tracks.length) await exact.publish(tracks);
                    return uid;
                }, async (exact, op, accepted) => {
                    if (accepted && exact.connectionState === "CONNECTED" && op.sessionId === this.session.id) return true;
                    if (exact.connectionState !== "DISCONNECTED") await exact.leave();
                    return exact.connectionState === "DISCONNECTED";
                }, () => { this.tokenResetInProgress = false; this.postRejoinSetup(); this.setRtcState("CONNECTED"); }, false).then(result => result !== null);
            }).then(ok => {
                this.tokenResetInProgress = false;
                if (!ok && !this.terminalOrLeaveLatch) this.setRtcState("FAILED");
                return ok;
            }).finally(() => { this.recoveryFlight = null; });
            return this.recoveryFlight;
        }

        bindClient(client) {
            if (!client || this.listenerClient === client) return;
            this.unbindClient();
            this.listenerClient = client;
            const generation = this.clientGeneration;
            const bind = (event, handler) => {
                const guarded = (...args) => {
                    if (this.listenerClient !== client || generation !== this.clientGeneration || this.terminalOrLeaveLatch) return;
                    handler(...args);
                };
                client.on(event, guarded);
                this.listeners.push({ client, event, guarded });
            };
            bind("token-privilege-will-expire", () => {
                if (this.rtcState === "CONNECTED" || this.rtcState === "RECONNECTING") this.renewToken();
            });
            bind("token-privilege-did-expire", () => this.recoverExpiredToken());
            bind("connection-state-change", (current, previous, reason) => this.onConnection(current, previous, reason));
            bind("peerconnection-state-change", (current, previous) => this.onPeerConnection(current, previous));
            bind("media-reconnect-start", uid => this.onMediaReconnectStart(uid));
            bind("media-reconnect-end", uid => this.onMediaReconnectEnd(uid));
            bind("exception", event => this.onException(event));
            this.customHandlers.forEach(item => bind(item.event, item.handler));
        }

        unbindClient() {
            this.listeners.splice(0).forEach(({ client, event, guarded }) => client.off(event, guarded));
            this.listenerClient = null;
        }

        onConnection(current, previous, reason) {
            const eventKey = `${this.clientGeneration}:${this.mediaGeneration}:${current}:${previous}:${reason || ""}`;
            if (eventKey === this.lastConnectionEvent) return;
            this.lastConnectionEvent = eventKey;
            if (current === "CONNECTING" && previous === "DISCONNECTED") this.setRtcState("JOINING");
            else if (current === "CONNECTED" && ["CONNECTING", "RECONNECTING", "DISCONNECTED"].includes(previous)) { this.clearRecoveryTimer(); this.setRtcState("CONNECTED"); }
            else if (current === "RECONNECTING" && ["CONNECTED", "CONNECTING"].includes(previous)) { this.setRtcState("RECONNECTING"); this.armRecoveryTimer(); }
            else if (current === "DISCONNECTED" && (reason === "LEAVE" || !reason) && previous === "DISCONNECTING") {
                if (this.tokenResetInProgress) this.setRtcState("JOINING");
                else { this.clearRecoveryTimer(); this.setRtcState("IDLE"); }
            }
            else if (current === "DISCONNECTED" && reason === "NETWORK_ERROR") {
                this.setRtcState("DISCONNECTED"); this.armRecoveryTimer(); this.recoverExpiredToken();
            }
            else if (current === "DISCONNECTED" && reason === "TOKEN_EXPIRE") {
                this.setRtcState("RECONNECTING"); this.armRecoveryTimer(); this.recoverExpiredToken();
            }
            else if (current === "DISCONNECTED" && new Set(["SERVER_ERROR", "UID_BANNED", "IP_BANNED", "CHANNEL_BANNED", "LICENSE_MISSING", "LICENSE_EXPIRED", "LICENSE_MINUTES_EXCEEDED", "LICENSE_PERIOD_INVALID", "LICENSE_MULTIPLE_SDK_SERVICE", "LICENSE_ILLEGAL", "UID_CONFLICT", "FALLBACK", "FALLBACK_TO_HLS"]).has(reason)) {
                this.clearRecoveryTimer(); this.setRtcState("FAILED");
            }
            else if (current === "DISCONNECTED") { this.clearRecoveryTimer(); this.setRtcState("FAILED"); }
            else if (current === "DISCONNECTING") {
                if (this.tokenResetInProgress) this.setRtcState("JOINING");
                else { this.clearRecoveryTimer(); this.setRtcState("LEAVING"); }
            }
            else this.onDiagnostic({ operationType: "CONNECTION_STATE", code: "UNKNOWN", stale: false });
        }

        onPeerConnection(current) {
            if (current === "disconnected") { this.setRtcState("RECONNECTING"); this.armRecoveryTimer(); }
            else if (current === "failed" || (current === "closed" && this.rtcState !== "LEAVING" && !this.tokenResetInProgress)) this.setRtcState("FAILED");
        }

        onMediaReconnectStart(uid) {
            if (this.mediaReconnectUids.has(uid)) return;
            this.mediaReconnectUids.add(uid);
            this.mediaReconnectState = "RECONNECTING";
            this.onState(this.snapshot());
            const generation = this.mediaGeneration;
            const timer = this.clock.setTimeout(() => {
                this.mediaReconnectTimers.delete(uid);
                if (generation === this.mediaGeneration && this.mediaReconnectUids.has(uid) && !this.terminalOrLeaveLatch) {
                    this.mediaReconnectState = "FAILED";
                    this.onState(this.snapshot());
                }
            }, DEADLINE_MS);
            this.mediaReconnectTimers.set(uid, timer);
        }

        onMediaReconnectEnd(uid) {
            if (!this.mediaReconnectUids.delete(uid)) return;
            const timer = this.mediaReconnectTimers.get(uid);
            if (timer !== undefined) this.clock.clearTimeout(timer);
            this.mediaReconnectTimers.delete(uid);
            this.mediaReconnectState = "RECOVERED";
            this.onState(this.snapshot());
            this.mediaReconnectState = "IDLE";
            this.onState(this.snapshot());
        }

        onException(event) {
            const videoIssues = new Set([1001, 1002, 1003, 1005]);
            const audioIssues = new Set([2001, 2002, 2003, 2005]);
            const videoRecovery = new Set([3001, 3002, 3003, 3005]);
            const audioRecovery = new Set([4001, 4002, 4003, 4005]);
            let eventCategory = "AGORA_EXCEPTION_UNKNOWN";
            if (videoIssues.has(event.code)) { eventCategory = "AGORA_EXCEPTION_VIDEO"; this.exceptionIssues.add(event.code); }
            else if (audioIssues.has(event.code)) { eventCategory = "AGORA_EXCEPTION_AUDIO"; this.exceptionIssues.add(event.code); }
            else if (videoRecovery.has(event.code)) { eventCategory = "AGORA_EXCEPTION_VIDEO_RECOVERED"; this.exceptionIssues.delete(event.code - 2000); }
            else if (audioRecovery.has(event.code)) { eventCategory = "AGORA_EXCEPTION_AUDIO_RECOVERED"; this.exceptionIssues.delete(event.code - 2000); }
            const code = Number.isInteger(event.code) ? event.code : "UNKNOWN";
            const diagnosticKey = `${this.clientGeneration}:${this.mediaGeneration}:${eventCategory}:${code}`;
            if (this.exceptionDiagnostics.has(diagnosticKey)) return;
            this.exceptionDiagnostics.add(diagnosticKey);
            this.onDiagnostic({ operationType: "EXCEPTION", eventCategory, code, stale: false });
        }

        armRecoveryTimer() {
            if (this.recoveryTimer !== null) return;
            const generation = this.mediaGeneration;
            this.recoveryTimer = this.clock.setTimeout(() => {
                this.recoveryTimer = null;
                if (generation === this.mediaGeneration && !this.terminalOrLeaveLatch) this.setRtcState("FAILED");
            }, DEADLINE_MS);
        }

        clearRecoveryTimer() {
            if (this.recoveryTimer === null) return;
            this.clock.clearTimeout(this.recoveryTimer);
            this.recoveryTimer = null;
        }

        setRtcState(state) { this.rtcState = state; this.onState(this.snapshot()); }
        snapshot() { return { rtcConnectionState: this.rtcState, mediaReconnectState: this.mediaReconnectState, tokenState: this.tokenState, cameraState: this.cameraState }; }

        markTerminal() {
            if (this.session) this.session.terminal = true;
            this.invalidate("TERMINAL", { replaceSession: true });
            this.setRtcState("LEAVING");
        }

        invalidate(reason, flags) {
            this.revokeCallFlow();
            this.cleanupGeneration++;
            if (flags && flags.replaceSession) this.mediaGeneration++;
            if (flags && flags.replaceClient) this.clientGeneration++;
            if (reason === "TERMINAL" || reason === "LEAVE" || reason === "DISPOSE" || reason === "AUTH_FATAL") this.terminalOrLeaveLatch = true;
            Array.from(this.currentOwners.values()).forEach(op => this.revoke(op, "REVOKED"));
            this.cancelRetryWaits();
            this.clearRecoveryTimer();
            this.mediaReconnectTimers.forEach(timer => this.clock.clearTimeout(timer));
            this.mediaReconnectTimers.clear();
            this.mediaReconnectUids.clear();
            this.mediaReconnectState = "IDLE";
        }

        async dispose(reason) {
            this.invalidate(reason || "DISPOSE", { replaceSession: true });
            this.unbindClient();
            const client = this.client;
            if (client) await this.retireClientOrResource(client, null);
            this.session = null;
            this.setRtcState("IDLE");
        }

        async cleanupCall(resources, reason) {
            this.invalidate(reason || "LEAVE", { replaceSession: true });
            this.unbindClient();
            for (const resource of resources || []) {
                const owner = { operationId: resource && resource.__ownerOperationId };
                this.cleanupResource(owner, resource);
            }
            const oldClient = this.client;
            if (oldClient) await this.retireClientOrResource(oldClient, null);
            this.replaceClient(oldClient);
            this.session = null;
            this.setRtcState("IDLE");
        }
    }

    class AdaptiveMediaController {
        constructor(options) {
            this.client = options.client;
            this.clock = options.clock || { now: () => performance.now() };
            this.getCurrentUid = options.getCurrentUid || (() => null);
            this.onState = options.onState || (() => {});
            this.onNotice = options.onNotice || (() => {});
            this.onVolume = options.onVolume || (() => {});
            this.states = new Map();
            this.adaptationEnabled = true;
            this.rtcState = "IDLE";
            this.mediaReconnectActive = false;
            this.reconnectDegraded = false;
            this.volumeEnabled = false;
            this.lastVolumeRenderAt = -Infinity;
            this.sessionId = null;
            this.generation = 0;
            this.boundListeners = [];
        }

        state(uid) {
            if (!this.states.has(uid)) this.states.set(uid, {
                goodConsecutive: 0, degradedConsecutive: 0, poorFallbackConsecutive: 0,
                lastSwitchAt: null, pendingStreamType: null, fallbackState: "HIGH", networkHealth: "UNKNOWN", renderedKey: null
            });
            return this.states.get(uid);
        }

        startSession(sessionId) {
            this.generation++;
            this.sessionId = sessionId;
            this.reset();
            this.onState({ fallbackState: "HIGH", networkHealth: "UNKNOWN", reset: true });
        }

        context() { return { sessionId: this.sessionId, generation: this.generation }; }
        isCurrent(context) { return !!context && context.sessionId === this.sessionId && context.generation === this.generation; }

        bindClient(client) {
            this.unbindClient();
            this.client = client;
            if (!client || typeof client.on !== "function") return;
            const context = this.context();
            const bind = (event, handler) => {
                const guarded = (...args) => { if (this.isCurrent(context)) handler(...args, context); };
                client.on(event, guarded); this.boundListeners.push({ client, event, guarded });
            };
            bind("network-quality", (stats, context) => this.onNetworkQuality(stats, context));
            bind("stream-type-changed", (uid, streamType, context) => this.onStreamTypeChanged(uid, streamType, context));
            bind("stream-fallback", (uid, action, context) => this.onStreamFallback(uid, action, context));
            bind("volume-indicator", (entries, context) => this.onVolumeIndicator(entries, context));
        }

        unbindClient() { this.boundListeners.splice(0).forEach(({ client, event, guarded }) => client.off(event, guarded)); }

        reset() {
            this.states.clear();
            this.adaptationEnabled = true;
            this.reconnectDegraded = false;
            this.rtcState = "IDLE";
            this.mediaReconnectActive = false;
            this.volumeEnabled = false;
            this.lastVolumeRenderAt = -Infinity;
        }

        async afterLocalVideoPublished(context = this.context()) {
            if (!this.isCurrent(context)) return false;
            if (!this.adaptationEnabled || !this.client || typeof this.client.enableDualStream !== "function") return this.disable("ADAPTATION_UNSUPPORTED");
            try { await this.client.enableDualStream(); if (!this.isCurrent(context)) return false; }
            catch (_) { if (this.isCurrent(context)) this.disable("ADAPTATION_UNAVAILABLE"); }
        }

        async configureRemote(uid, context = this.context()) {
            if (!this.isCurrent(context)) return false;
            if (!this.adaptationEnabled || !this.client || typeof this.client.setRemoteVideoStreamType !== "function" || typeof this.client.setStreamFallbackOption !== "function") return this.disable("ADAPTATION_UNSUPPORTED");
            try {
                await this.client.setRemoteVideoStreamType(uid, 0); if (!this.isCurrent(context)) return false;
                await this.client.setStreamFallbackOption(uid, 2); if (!this.isCurrent(context)) return false;
            } catch (_) { if (this.isCurrent(context)) this.disable("ADAPTATION_UNAVAILABLE"); }
        }

        async enableVolume(context = this.context()) {
            if (!this.isCurrent(context)) return false;
            if (!this.client || typeof this.client.enableAudioVolumeIndicator !== "function") return;
            try { await this.client.enableAudioVolumeIndicator(); if (this.isCurrent(context)) this.volumeEnabled = true; }
            catch (_) { if (this.isCurrent(context)) this.onNotice("VOLUME_INDICATOR_UNAVAILABLE"); }
        }

        disable(code) { this.adaptationEnabled = false; this.onNotice(code); return false; }

        onRtcState(rtcState, mediaReconnectState, context = this.context()) {
            if (!this.isCurrent(context)) return;
            this.rtcState = rtcState;
            this.mediaReconnectActive = mediaReconnectState === "RECONNECTING";
            if (rtcState === "RECONNECTING" || this.mediaReconnectActive) {
                this.states.forEach((state, uid) => {
                    this.resetCounters(state); state.networkHealth = "RECONNECTING";
                    this.renderState(uid, state);
                    if (!this.reconnectDegraded) void this.requestStream(uid, 1, true, context);
                });
                this.reconnectDegraded = true;
            } else if (rtcState === "CONNECTED") {
                this.states.forEach((state, uid) => { this.resetCounters(state); state.networkHealth = "UNKNOWN"; this.renderState(uid, state); });
                this.reconnectDegraded = false;
            }
        }

        resetCounters(state) { state.goodConsecutive = 0; state.degradedConsecutive = 0; state.poorFallbackConsecutive = 0; }

        renderState(uid, state) {
            const key = `${state.networkHealth}:${state.fallbackState}`;
            if (state.renderedKey === key) return;
            state.renderedKey = key;
            this.onState({ uid, fallbackState: state.fallbackState, networkHealth: state.networkHealth });
        }

        onNetworkQuality(sample, context = this.context()) {
            if (!this.isCurrent(context) || !this.adaptationEnabled || !sample) return;
            const uid = sample.uid == null ? this.getCurrentUid() : sample.uid;
            if (uid == null) return;
            const quality = Number(sample.downlinkNetworkQuality);
            const state = this.state(uid);
            if (this.rtcState === "RECONNECTING" || this.mediaReconnectActive) {
                this.resetCounters(state); state.networkHealth = "RECONNECTING"; this.renderState(uid, state);
                if (!this.reconnectDegraded) { this.reconnectDegraded = true; void this.requestStream(uid, 1, true, context); }
                return;
            }
            if (quality === 0) { this.resetCounters(state); state.networkHealth = "UNKNOWN"; this.renderState(uid, state); return; }
            if (quality >= 1 && quality <= 2) {
                state.goodConsecutive = Math.min(5, state.goodConsecutive + 1); state.degradedConsecutive = 0; state.poorFallbackConsecutive = 0; state.networkHealth = "GOOD";
                this.renderState(uid, state);
                if (state.goodConsecutive === 5 && this.rtcState === "CONNECTED" && !this.mediaReconnectActive) void this.requestStream(uid, 0, false, context);
                return;
            }
            if (quality === 3) { this.resetCounters(state); state.networkHealth = "UNSTABLE"; this.renderState(uid, state); return; }
            if (quality >= 4 && quality <= 6) {
                state.goodConsecutive = 0; state.degradedConsecutive = Math.min(3, state.degradedConsecutive + 1); state.poorFallbackConsecutive = 0; state.networkHealth = "POOR";
                this.renderState(uid, state);
                if (state.degradedConsecutive === 3) void this.requestStream(uid, 1, false, context);
            }
        }

        async requestStream(uid, streamType, immediate, context = this.context()) {
            if (!this.isCurrent(context)) return false;
            const state = this.state(uid);
            if (!this.adaptationEnabled || state.pendingStreamType === streamType) return false;
            const now = this.clock.now();
            if (!immediate && state.lastSwitchAt !== null && now - state.lastSwitchAt < 10000) return false;
            if (!this.client || typeof this.client.setRemoteVideoStreamType !== "function") return this.disable("ADAPTATION_UNSUPPORTED");
            state.pendingStreamType = streamType;
            try { await this.client.setRemoteVideoStreamType(uid, streamType); if (!this.isCurrent(context)) return false; state.lastSwitchAt = now; return true; }
            catch (_) { if (this.isCurrent(context)) this.disable("ADAPTATION_UNAVAILABLE"); return false; }
            finally { if (this.isCurrent(context)) state.pendingStreamType = null; }
        }

        onStreamTypeChanged(uid, streamType, context = this.context()) {
            if (!this.isCurrent(context)) return;
            const state = this.state(uid); state.fallbackState = streamType === 1 ? "LOW" : "HIGH"; this.renderState(uid, state);
        }

        onStreamFallback(uid, action, context = this.context()) {
            if (!this.isCurrent(context)) return;
            const state = this.state(uid); state.fallbackState = action === "fallback" ? "AUDIO_ONLY" : "HIGH"; this.renderState(uid, state);
        }

        onVolumeIndicator(entries, context = this.context()) {
            if (!this.isCurrent(context) || !this.volumeEnabled || this.clock.now() - this.lastVolumeRenderAt < 250) return;
            this.lastVolumeRenderAt = this.clock.now();
            const active = (entries || []).filter(entry => Number(entry.level) > 60).map(entry => ({ uid: entry.uid, active: true }));
            this.onVolume(active);
        }
    }

    return { AgoraReliabilityController, AdaptiveMediaController, DEADLINE_MS, classifyAgoraError };
}));
