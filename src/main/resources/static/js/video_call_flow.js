(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    else root.VideoCallFlow = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    class CallFlowOrchestrator {
        constructor(dependencies) { this.dependencies = dependencies; }

        async run(channelName, sessionId) {
            const d = this.dependencies;
            const flow = d.reliability.startCallFlow(sessionId);
            if (!flow) return { status: "STALE" };
            flow.channelName = channelName;
            flow.kind = d.isRecovery ? (d.isRecovery() ? "RECOVERY" : "INITIAL") : "INITIAL";
            // The controller owns this identity; fields are captured once and never reassigned by the flow.
            const context = flow;
            let bundle = { tracks: [], audioTrack: null, videoTrack: null };
            const current = () => d.reliability.isCurrentCallFlow(context);
            const stale = async () => {
                if (bundle) await d.cleanupOwnedBundle(context, bundle);
                return { status: "STALE", flowId: context.flowId, kind: context.kind };
            };
            try {
                if (!current()) return stale();
                const tokenData = await d.fetchInitialToken(context);
                if (!current() || !tokenData) return stale();
                if (tokenData.channelName !== channelName) return stale();

                await d.join(context, tokenData);
                if (!current()) return stale();

                bundle.audioTrack = await d.createMicrophone(context);
                if (!current()) return stale();
                if (!bundle.audioTrack) throw Object.assign(new Error(), { code: "DEVICE_NOT_FOUND" });
                bundle.tracks.push(bundle.audioTrack);

                try { bundle.videoTrack = await d.createCamera(context); }
                catch (cameraError) {
                    if (!current()) return stale();
                    d.onOptionalCameraFailure(context, cameraError);
                    bundle.videoTrack = null;
                }
                if (!current()) return stale();
                if (bundle.videoTrack) bundle.tracks.push(bundle.videoTrack);
                else d.markCameraDegraded(context);
                if (!current()) return stale();
                d.adoptTracks(context, bundle);
                if (!current()) return stale();

                await d.publish(context, bundle.tracks);
                if (!current()) return stale();

                const confirmed = await d.confirmJoin(context, sessionId);
                if (!current() || !confirmed) return stale();

                if (d.isRecovery()) {
                    const completed = await d.publishRecoveryComplete(context, sessionId);
                    if (!current() || !completed) return stale();
                    d.markRecoveryComplete(context);
                }
                return { status: bundle.videoTrack ? "CONNECTED_AV" : "CONNECTED_AUDIO_ONLY", flowId: context.flowId, kind: context.kind };
            } catch (error) {
                if (!current()) return stale();
                if (context.kind === "RECOVERY") await d.publishRecoveryFailure(context, sessionId);
                if (!current()) return stale();
                await d.cleanupCurrentFlow(context, bundle, error);
                return { status: error && error.mediaKind === "microphone" ? "FAILED_MICROPHONE" : "FAILED", flowId: context.flowId, kind: context.kind };
            }
        }
    }

    return { CallFlowOrchestrator };
}));
