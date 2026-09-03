# Spec: Agora Call Reliability — Giai đoạn 4

## 1. Trạng thái tài liệu

- **APPROVED — đã qua independent Review và được Lead phê duyệt để triển khai.**
- Quy trình bắt buộc: `DRAFT -> independent Review -> Lead decision`.
- Chỉ Lead được chỉ đạo đổi trạng thái sang `APPROVED`. Tác giả và Implement worker không tự phê duyệt.
- Baseline: branch `feature/agora-reliability`, commit `52b2c82330d1589639da040cfebfaf662320eb42` từ `origin/feature/learning-session-v2`.
- Spec này bổ sung độ tin cậy của media Agora; không thay đổi semantics đã nghiệm thu của Lifecycle V2.

## 2. Mục tiêu

Cuộc gọi 1-1 phải chịu được token expiry, mạng chập chờn, thiết bị thay đổi, autoplay bị chặn và các thao tác join/leave/recovery chồng lấn mà:

- không tạo thêm `LearningSession` hoặc `SessionPresence` ngoài workflow Lifecycle V2 hiện hữu;
- không để completion async cũ ghi đè trạng thái mới;
- không biến sự kiện media/RTC thành domain lifecycle command;
- giữ microphone là điều kiện bắt buộc của sản phẩm luyện nói, trong khi camera có thể degrade sang audio-only;
- cleanup tài nguyên và listener theo ownership rõ ràng, idempotent;
- không làm lộ token, credential, media hoặc dữ liệu thiết bị nhạy cảm.

## 3. Phạm vi và ngoài phạm vi

### 3.1. Trong phạm vi

- Pin Agora Web SDK chính xác và reproducible.
- RTC/UI state machine độc lập với domain session state.
- Serialized join, leave, token renewal và media recovery.
- Pre-call capability/device/permission checks.
- Device hot-plug recovery, autoplay/audio-context recovery.
- P0 reliability; P1 adaptive stream và volume indicator chỉ sau khi toàn bộ P0 PASS.
- Unit/mock, Spring security/controller integration và manual two-browser verification.
- Thay đổi frontend/backend tối thiểu cần thiết để dùng token endpoint đã chốt, nhưng không thay đổi Lifecycle V2 semantics.

### 3.2. Ngoài phạm vi — danh sách cố định

- Migration mới hoặc sửa migration hiện hữu, trừ khi Lead phê duyệt một spec update riêng; decision hiện tại là **không migration**.
- Production runtime configuration, production dependency hoặc production build pipeline mới.
- Admin.
- Peer Rating.
- Screen sharing.
- Cloud recording.
- Livestream-CDN.
- Group call.
- RTM.
- Reactions.
- Data streams.
- Virtual background.
- Beauty effects.
- Raw audio/video processing.
- Paid AI noise suppression.

Test-only Node harness/scripts, test-profile/environment overrides và bổ sung CI command tối thiểu để chạy gate của spec này được phép; không được thêm secret default hoặc redesign CI không liên quan.

## 4. Agora Web SDK được chốt

### 4.1. Release và artifact

- Release: **Agora Web SDK NG 4.24.7**.
- URL immutable bắt buộc:

  `https://download.agora.io/sdk/release/AgoraRTC_N-4.24.7.js`

- Cấm dùng URL mutable `https://download.agora.io/sdk/release/AgoraRTC_N.js`.
- Không thêm npm/Gradle dependency hoặc bundling pipeline; tiếp tục load browser script trực tiếp.
- Script tag phải có:

  `integrity="sha384-MBpKUUYG0z1jIM5tRY5ztT0dLJfW9FVxbKHGmzSm3QcK+EvqQcGl0lCFKQ3CZB3y" crossorigin="anonymous"`

- SHA-256 audit: `5feb64777698f32868bdd5301061ada4ba2bc47e17b43b7ee8138a5222dda466`.
- Kích thước artifact: `1,591,935` byte.

### 4.2. Bằng chứng release

- Agora-hosted versioned artifact trả `200`, `Content-Length: 1591935`, `Last-Modified: 2026-08-03T08:44:59Z`, ETag `0295E7695290BC2FC09113DFC6EE8AD4`.
- Registry package `agora-rtc-sdk-ng@4.24.7` do maintainers `agora.io` và `agorabuilder` phát hành ngày `2026-08-03T08:52:44.544Z`; repository khai báo `AgoraIO/agora-rtc-web`; npm integrity là `sha512-OboKRrbtWIT2xbbROq+2B0lcXzEChnCX3NYju0ZkkKD7uXwn1DXp2l9wq1rIsrv9/UGBqOZrhrIpOZwncZQj5A==`.
- Banner bên trong artifact có suffix build `dirty`; suffix này không được dùng làm căn cứ release. Release được chấp nhận vì cùng version 4.24.7 tồn tại đồng thời trên official versioned `download.agora.io` và package do Agora maintainers phát hành, đồng thời byte artifact được khóa bằng SRI/SHA-256 ở trên.
- API/signature được đối chiếu từ type declaration versioned `https://cdn.jsdelivr.net/npm/agora-rtc-sdk-ng@4.24.7/rtc-sdk_en.d.ts`; implementation không được suy diễn signature từ version khác.

## 5. Boundary với Backend Lifecycle V2

Backend Lifecycle V2 là nguồn domain chính thức cho `MATCHED`, `IN_PROGRESS`, presence interval, reconnect deadline và terminal state.

- Không refactor semantics đã nghiệm thu của WebSocket disconnect/presence trong task này.
- Coupling hiện hữu `WebSocket disconnect -> presence signal` là inherited V2 behavior.
- Không mở rộng thành `Agora event -> domain lifecycle`.
- `connection-state-change`, `network-quality`, token expiry, `media-reconnect-*`, `exception`, device events, autoplay và fallback chỉ được đổi RTC/media/UI state.
- Các event trên không được gọi `leave-agora`, không gửi `/app/end-call`, không finalize, không enqueue, không tạo session và không tạo presence interval.
- Các command WebSocket/recovery và HTTP join/leave hiện hữu tiếp tục authoritative.
- Chỉ explicit user end, existing WS lifecycle/recovery hoặc backend terminal response được phép dẫn đến domain transition theo V2.
- Automated acceptance phải snapshot/mock domain command calls và chứng minh mọi transient/failure Agora đều có **zero** domain lifecycle mutation.

## 6. State model phía client

Client giữ **năm** dimension độc lập:

| State | Giá trị hợp lệ |
|---|---|
| `domainSessionState` | `NONE`, `MATCHED`, `IN_PROGRESS`, `TERMINAL` |
| `rtcConnectionState` | `IDLE`, `JOINING`, `CONNECTED`, `RECONNECTING`, `DISCONNECTED`, `LEAVING`, `FAILED` |
| `mediaReconnectState` | `IDLE`, `RECONNECTING`, `RECOVERED`, `FAILED` |
| `networkHealth` | `UNKNOWN`, `GOOD`, `UNSTABLE`, `POOR` |
| `fallbackState` | `HIGH`, `LOW`, `AUDIO_ONLY` |

### 6.1. Precedence nhãn UI

Một nhãn call-status duy nhất được tính theo thứ tự cao xuống thấp:

1. `recovery failed`
2. `disconnected`
3. `reconnecting`
4. `unstable`
5. `connected`

Backend terminal luôn đóng call view và vô hiệu mọi nhãn RTC. Trạng thái pre-call/matched không được giả thành `connected`.

### 6.2. Legal transitions

```text
IDLE -> JOINING -> CONNECTED
JOINING -> FAILED | LEAVING
CONNECTED -> RECONNECTING | LEAVING | DISCONNECTED
RECONNECTING -> CONNECTED | DISCONNECTED | FAILED | LEAVING
DISCONNECTED -> JOINING | LEAVING | FAILED
FAILED -> JOINING | LEAVING
LEAVING -> IDLE
```

- `domainSessionState=TERMINAL` chỉ cho phép RTC đi về `LEAVING -> IDLE`; không được join/recover lại.
- `mediaReconnectState`: `IDLE -> RECONNECTING -> RECOVERED -> IDLE`; bất kỳ recovery error nào đi `FAILED`, chỉ explicit retry thành công hoặc leave reset về `IDLE`.
- Network/fallback state không được đổi domain state.

`domainSessionState` chỉ mirror explicit backend message hoặc authenticated active-session response. Client không infer `IN_PROGRESS` từ local `client.join`, publish, HTTP `join-agora` `200`, remote media hoặc Agora event.

### 6.3. Normative event -> guard -> state/action matrix

Mọi row đều áp dụng rule chung: event mang/captured generation cũ là no-op; duplicate idempotent không chạy side effect lần hai; handler không được POST `leave-agora`, gửi `/app/end-call`, finalize, enqueue, tạo `LearningSession` hoặc tạo `SessionPresence`, trừ row backend command mô tả rõ existing authoritative workflow. Cột “domain effect” khóa explicit forbidden effect.

| Source/event | Guard stale/duplicate/generation | State/UI action | Domain effect |
|---|---|---|---|
| Backend `MATCHED` | Chỉ current authenticated WS connection; duplicate cùng session reuse join Promise; khác active session bị reject/re-resolve active API | Mirror `domain=MATCHED`, mở pre-call, new generation nếu chưa sở hữu session | Chỉ mirror backend; không tự tạo session/presence |
| Backend `RECOVERY_READY` | Current WS + same active session; duplicate coalesce recovery Promise | Mirror backend active state (không tự suy `IN_PROGRESS`), `rtc=JOINING`, recovery UI | Existing recovery command authoritative; handler không tự tạo domain object |
| Backend `PEER_RECONNECTING` | Same session/current generation; duplicate no-op | Status precedence `reconnecting`; không đổi local RTC transport | Zero mutation |
| Backend `PEER_RECOVERED` | Same session/current generation | Bỏ peer-reconnecting banner; nhãn tính lại từ năm dimensions | Zero mutation |
| Backend `SESSION_ENDED` | Same session hoặc explicit backend terminal notification; duplicate cleanup Promise | Mirror `domain=TERMINAL`, invalidate generation, serialized cleanup, terminal UI | Backend đã authoritative terminal; client không gửi thêm lifecycle command |
| Backend `NO_ACTIVE_SESSION` | Chỉ response cho recovery request hiện hành | Mirror `domain=NONE`, cleanup preview/media, setup UI | Zero mutation |
| Backend `ACTIVE_SESSION_EXISTS` | Coalesce active-session fetch; ignore stale connection | Không enqueue; fetch authenticated active session rồi mirror response | Existing recovery workflow only; zero direct mutation |
| Backend `PEER_DISCONNECTED` | Same session/current WS; duplicate cleanup idempotent | Peer-left UX, remote render cleanup; local transport cleanup theo accepted UI flow | Mirror existing V2 signal; không Agora-derived mutation |
| Active-session HTTP response | Response generation/session request current | Mirror explicit `MATCHED/IN_PROGRESS/TERMINAL` payload only | Read-only; zero mutation |
| Agora `connection-state-change(cur, prev, reason)` | Client singleton + active generation; duplicate tuple no-op; áp dụng bảng 6.3.1 | Map deterministic theo bảng 6.3.1 | Zero forbidden domain effect |
| Agora `peerconnection-state-change(cur, prev)` | Client singleton + active generation; duplicate tuple no-op; áp dụng bảng 6.3.2 | Diagnostic/media recovery theo bảng 6.3.2; không dùng enum của `connection-state-change` | Zero forbidden domain effect |
| Agora `media-reconnect-start(uid)` | Active generation; per-UID duplicate coalesced | `mediaReconnect=RECONNECTING`; reconnecting UI | Zero forbidden domain effect |
| Agora `media-reconnect-end(uid)` | Must match outstanding per-UID start/current generation | `RECOVERED`, then `IDLE`; recalc label | Zero forbidden domain effect |
| Agora token `will-expire` | Active session + `CONNECTED/RECONNECTING`; token Promise single-flight | Fetch + `renewToken`; degraded UX on exhaustion | Zero forbidden domain effect |
| Agora token `did-expire` | Active session/current generation; recovery Promise single-flight; explicit leave/terminal wins | Serialized transport reset/rejoin same channel/UID and republish exactly once | No backend join/leave; zero domain mutation |
| Agora `user-published(user, mediaType)` | Current generation; per-user/media duplicate subscription coalesced | Subscribe/play only named mediaType; autoplay policy applies | Zero forbidden domain effect |
| Agora `user-unpublished(user, mediaType)` | Current generation; duplicate absent media no-op | Stop/clear only named mediaType; audio unpublish không clear video | Zero forbidden domain effect |
| Agora `user-left(user, reason)` | Current generation; per-UID cleanup once | Clear that remote user's media, peer-media-left UX; do not declare backend terminal | Zero forbidden domain effect |
| Module `camera-changed` | Current generation; serialized device operation; duplicate device state no-op | Preserve/switch/fallback camera per section 9 | Zero forbidden domain effect |
| Module `microphone-changed` | Same as camera | Blocking mic degraded/retry or safe switch | Zero forbidden domain effect |
| Module `playback-device-changed` | Feature supported + current generation | Preserve/switch/default-output notice | Zero forbidden domain effect |
| Module `autoplay-failed` | Current generation; coalesce CTA by blocked media kind | Critical audio CTA hoặc non-blocking video retry | Zero forbidden domain effect |
| Module `audio-context-state-changed` | Current generation; duplicate state no-op | Interrupted => CTA; running => verify actual play outcome | Zero forbidden domain effect |
| Agora `stream-type-changed` | P1 enabled + current generation/remote UID | Mirror actual HIGH/LOW fallback dimension/UI | Zero forbidden domain effect |
| Agora `stream-fallback` | P1 enabled + current generation/remote UID | Mirror actual AUDIO_ONLY/recover UI | Zero forbidden domain effect |
| Agora `network-quality` | P1 enabled; current generation; 2s sample policy | Update health/counters/adaptation only | Zero forbidden domain effect |
| Agora `volume-indicator` | P1 enabled/current generation; throttle | Ephemeral mic/speaker UI only | Zero persistence/domain effect |
| Agora `exception({code, msg, uid})` | Exact 4.24.7 event object; current generation; chỉ dùng numeric `code`, không đọc/phân loại từ raw `msg`; duplicate rate-limited | Diagnostic/recovery UX theo bảng 6.5; event này một mình không bao giờ fatal/terminal | Zero forbidden domain effect |

#### 6.3.1. `connection-state-change` — mapping bắt buộc

Nguồn type chính xác là `rtc-sdk_en.d.ts` 4.24.7: `ConnectionState` chỉ gồm `DISCONNECTED`, `CONNECTING`, `RECONNECTING`, `CONNECTED`, `DISCONNECTING`; `reason` chỉ có khi current là `DISCONNECTED` và thuộc `ConnectionDisconnectedReason`. Không phát minh trạng thái SDK `FAILED`. `FAILED` chỉ là application `rtcConnectionState` sau terminal condition trong bảng.

| Agora event | Current | Previous | Reason/category | Operation context | UI RTC state kết quả | Lifecycle/domain effect | Retry/recovery action | Terminal condition |
|---|---|---|---|---|---|---|---|---|
| `connection-state-change` | `CONNECTING` | `DISCONNECTED` | reason absent | Initial join current generation | `JOINING` | Zero mutation | Await chính join Promise; không tạo retry song song | Join Promise reject sau token retry policy => `FAILED`; explicit retry/user end only |
| `connection-state-change` | `CONNECTED` | `CONNECTING` | reason absent | Initial join | `CONNECTED`; nhãn `connected` sau publish/ownership setup thành công | Existing HTTP `join-agora` workflow duy nhất; event không gọi endpoint | Không retry; reset disconnected recovery budget/timer | Không |
| Join Promise reject | n/a | n/a | Sanitized `AgoraRTCErrorCode` theo 6.5 | Initial join | `FAILED`, `recovery failed` | Zero mutation; chưa POST `join-agora` nếu media join/publish chưa hoàn tất | Chỉ explicit retry tạo generation/operation mới | Hết operation retry hoặc non-retryable error |
| `connection-state-change` | `RECONNECTING` | `CONNECTED` hoặc `CONNECTING` | reason absent | SDK transient recovery, không explicit leave/token reset | `RECONNECTING`, nhãn `reconnecting`; bắt đầu một recovery deadline **15 giây** monotonic cho generation | Zero mutation | SDK tự reconnect; không gọi `join`; coalesce duplicate | Chưa terminal; deadline quyết định ở row timeout |
| `connection-state-change` | `CONNECTED` | `RECONNECTING` | reason absent | Transient recovery thành công | `CONNECTED`; nhãn tính lại; reset recovery deadline và toàn bộ network counters theo 11.3 | Zero mutation | Không retry; tiếp tục owned media/subscriptions | Không |
| `connection-state-change` | `CONNECTING` | `DISCONNECTED` | reason absent | Serialized transient/token recovery đã được backend token endpoint cho phép | `JOINING`; giữ recovery CTA/banner theo operation context | Zero mutation | Await duy nhất recovery join Promise | Promise fail/budget hết => app `FAILED` |
| `connection-state-change` | `CONNECTED` | `CONNECTING` hoặc `DISCONNECTED` | reason absent | Serialized transient/token recovery | `CONNECTED`; cancel deadline, reset counters theo 11.3; republish/setup exactly once theo owner operation | Zero mutation | Clear recovery Promise; không POST backend join/leave | Không |
| `connection-state-change` | `DISCONNECTED` | `RECONNECTING` hoặc `CONNECTED` | `NETWORK_ERROR` | Transient network; không leave/token reset | `DISCONNECTED`, nhãn `disconnected`; giữ cùng recovery deadline nếu đã chạy, nếu chưa thì bắt đầu 15 giây | Zero mutation | Một serialized recovery attempt: fetch token qua backend rồi reset/join same session theo 7.4; không backend join/leave | Recovery deadline 15 giây hết hoặc operation budget hết => `FAILED`, `recovery failed`; không domain terminal |
| Recovery deadline timeout | n/a | n/a | transient budget exhausted | Không explicit leave, không backend terminal | `FAILED`, nhãn `recovery failed` | Zero mutation | Hủy timer/attempt, chờ explicit retry/end hoặc backend lifecycle | Application media-terminal only; domain vẫn authoritative |
| `media-reconnect-start(uid)` | n/a | n/a | SDK media path | Active generation, không leave | Giữ RTC state; `mediaReconnect=RECONNECTING`, nhãn `reconnecting`; reconnect override network UI | Zero mutation | Coalesce theo UID; SDK tự recover; dùng cùng 15 giây deadline nếu RTC cũng reconnect, nếu media-only thì tạo 15 giây deadline | `media-reconnect-end` không đến trước deadline => `mediaReconnect=FAILED`, UI `recovery failed`; không domain terminal |
| `media-reconnect-end(uid)` | n/a | n/a | SDK media path recovered | Matching outstanding start | `mediaReconnect=RECOVERED` rồi `IDLE`; RTC label tính lại; reset counters theo 11.3 | Zero mutation | Cancel media deadline, không join | Không |
| `connection-state-change` | `DISCONNECTING` | `CONNECTED`, `RECONNECTING` hoặc `CONNECTING` | reason absent | Explicit local user leave/current cleanup generation | `LEAVING`; không hiện reconnect | Chỉ explicit existing V2 end/leave command được gọi bởi user workflow, không bởi event | Cancel mọi retry/deadline; không auto-join | Không; chờ cleanup |
| `connection-state-change` | `DISCONNECTED` | `DISCONNECTING` | `LEAVE` hoặc absent | Explicit local leave hoặc serialized transport leave | Explicit user leave: `IDLE` sau cleanup; token transport reset: giữ internal recovery UI, không hiện transient reconnect | Explicit user workflow giữ V2 semantics; token transport leave zero mutation | User leave: không retry. Token reset: tiếp tục join đúng một lần chỉ khi backend token fetch đã cho phép | Không |
| Token `will-expire` / renew pending | giữ SDK current | giữ SDK previous | token warning | `CONNECTED/RECONNECTING`, single-flight | Giữ RTC state; non-terminal “đang gia hạn” | Zero mutation | Section 7.3, 3 attempts total | Không |
| `renewToken` success | giữ SDK current | n/a | token renewed | Same generation/session/channel/UID | Trở lại nhãn theo RTC/network | Zero mutation | Clear single-flight; không rejoin | Không |
| `renewToken` failure | giữ SDK current | n/a | HTTP/SDK sanitized category | Will-expire | Giữ RTC; degraded token UX | Zero mutation | Hết retry thì chờ did-expire hoặc explicit retry; không tự leave | Không tự terminal |
| Token `did-expire` hoặc `DISCONNECTED` reason `TOKEN_EXPIRE` | `DISCONNECTED` hoặc current state trước reset | any | `TOKEN_EXPIRE` | Same active nonterminal backend session | `DISCONNECTED` rồi `JOINING`; token-recovery UI, không coi transient network | Zero mutation; không POST backend join/leave | Fetch authenticated token; chỉ response success mới serialized leave-if-needed/join theo 7.4 | Backend `409` hoặc terminal notification => terminal row; retry exhaustion => app `FAILED` |
| Token fetch | any | any | HTTP `409` | Will/did-expire recovery | Đóng call view/terminal UX sau mirror/re-resolution; không Agora retry | Không tự infer domain; backend response authoritative, không tạo session/presence | Cancel renewal/rejoin, invalidate generation, cleanup | Backend-terminal condition; Agora event sau đó stale/no-op |
| Backend `SESSION_ENDED`/explicit terminal response | any | any | backend authoritative | Bất kỳ join/reconnect/token/device operation | `LEAVING -> IDLE`, đóng call view, trở về matchmaking | Mirror backend terminal only | Cancel timers/queues, invalidate generation, cleanup tracks/listeners/UI; không auto-join | Domain terminal; không Agora event nào resurrect session |
| `connection-state-change` | `DISCONNECTED` | any ngoài explicit leave/token | `SERVER_ERROR`, `UID_BANNED`, `IP_BANNED`, `CHANNEL_BANNED`, `LICENSE_MISSING`, `LICENSE_EXPIRED`, `LICENSE_MINUTES_EXCEEDED`, `LICENSE_PERIOD_INVALID`, `LICENSE_MULTIPLE_SDK_SERVICE`, `LICENSE_ILLEGAL`, `UID_CONFLICT`, `FALLBACK`, `FALLBACK_TO_HLS` | SDK/server failure | `FAILED`, `recovery failed` | Zero mutation | Không automatic Agora retry; explicit user action/backend resolution only | Application media-terminal; domain không tự terminal |
| `connection-state-change` | `DISCONNECTED` | any | missing/unknown future reason | Không explicit leave/token reset | `FAILED`, generic `recovery failed` | Zero mutation | Không retry ngầm; explicit retry chỉ theo operation policy | Safe default application media-terminal only |
| `connection-state-change` | Valid current/previous tuple chưa liệt kê | any | any | Current generation | Giữ state an toàn hiện tại, generic diagnostic; không suy connected/reconnecting | Zero mutation | Không side effect/retry ngầm; re-resolve client state trong serialized owner operation | Nếu owner operation không thể xác định state hoặc hết budget => app `FAILED` |

#### 6.3.2. `peerconnection-state-change` — mapping riêng

Type 4.24.7 của event này là browser `RTCPeerConnectionState`, không phải `ConnectionState`: `new`, `connecting`, `connected`, `disconnected`, `failed`, `closed` (lowercase). Nó không có `reason`.

| Agora event | Current | Previous | Reason/category | Operation context | UI RTC state kết quả | Lifecycle/domain effect | Retry/recovery action | Terminal condition |
|---|---|---|---|---|---|---|---|---|
| `peerconnection-state-change` | `new` hoặc `connecting` | any | WebRTC peer connection | Initial/subscription setup | Không override `JOINING/RECONNECTING`; diagnostic only | Zero mutation | Await owning operation | Không |
| `peerconnection-state-change` | `connected` | `connecting`/`disconnected` | WebRTC recovered | Active generation | Không tự đặt RTC `CONNECTED`; clear peer diagnostic khi Agora connection cũng `CONNECTED` | Zero mutation | Không retry | Không |
| `peerconnection-state-change` | `disconnected` | `connected` | Potential transient | Không explicit leave | `RECONNECTING`, cùng 15 giây recovery deadline | Zero mutation | Chờ SDK `connection-state-change`/media reconnect; không tự join song song | Deadline hết => app `FAILED` |
| `peerconnection-state-change` | `failed` | any | WebRTC fatal for current PC | Active nonterminal generation | `FAILED`, `recovery failed` | Zero mutation | Dừng current media recovery; explicit retry có thể tạo/reset PC theo serialized policy | Application media-terminal only |
| `peerconnection-state-change` | `closed` | any | Expected cleanup nếu `LEAVING`/token reset; unexpected nếu đang active | Leave/reset hoặc active call | Expected: giữ leave/reset state; unexpected: `FAILED` | Zero mutation | Expected không retry; unexpected chỉ explicit retry | Unexpected close là application media-terminal |

### 6.4. Single-flight và generation

Mọi Agora/media operation bất đồng bộ chịu deadline, kể cả operation SDK không hỗ trợ cancellation thật, phải tuân theo contract ownership dưới đây. “Ignore stale callback” riêng lẻ không đủ để implement contract này.

#### 6.4.1. Identity, scope và ownership state

Mỗi operation có immutable identity được tạo đúng một lần ngay trước khi bắt đầu thực thi API bất đồng bộ. Với API không cần chờ shared-mutation lease, đây là lúc operation bắt đầu trực tiếp. Với API dùng FIFO lease, item đang chờ chỉ là `QueuedIntent`, **không phải media operation**, chưa có `operationId`, `startedAt`, `deadlineAt`, timer hoặc ownership trong `currentOwner`; identity dưới đây chỉ được tạo sau khi intent acquire lease và vượt qua pre-SDK revalidation:

- `operationId`: opaque unique ID, không reuse;
- `operationType`: một trong `CREATE_MIC_TRACK`, `CREATE_CAMERA_TRACK`, `JOIN`, `PUBLISH`, `SUBSCRIBE`, `DEVICE_SWITCH`, `TOKEN_FETCH`, `TOKEN_RENEW`, `TRANSPORT_RECOVERY` hoặc `CLEANUP_LEAVE`;
- `mediaGeneration`: số tăng đơn điệu mỗi lần retry/recovery/join mới nhận ownership, client bị replace hoặc toàn call bị invalidate;
- `cleanupGeneration`: số tăng đơn điệu mỗi lần bắt đầu cleanup/invalidation; dùng để ngăn completion trước cleanup publish/mutate trở lại;
- `clientGeneration`: identity của Agora client instance hiện hành; client replacement luôn tăng giá trị này;
- `sessionId`, `startedAt`, `deadlineAt=startedAt+15_000ms` tính bằng monotonic clock;
- `ownershipState`: `CURRENT`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` hoặc `REVOKED`.

`operation scope` là key deterministic theo loại tài nguyên: call transport; local `mic`/`camera`; remote `sessionId+uid+mediaType`; device kind; hoặc token renewal của `sessionId+clientGeneration`. Khi operation thật sự bắt đầu, implementation phải tăng `mediaGeneration` nếu đây là retry/recovery/call replacement, tạo `operationId` mới, atomically ghi operation đó là `currentOwner[scope]`, rồi callback/Promise capture toàn bộ identity trên. Operation cũ trong cùng scope chuyển `REVOKED` và vĩnh viễn không thể lấy lại ownership. Join cùng đúng owner/session đang chạy vẫn coalesce cùng Promise; nó không tạo operation mới. Mọi join, leave, did-expire rejoin và transport recovery chạy qua một serialized queue/mutex; token renewal có scope/single-flight riêng nhưng vẫn chịu call ordering. Việc enqueue retry/recovery chỉ tạo immutable `QueuedIntent` chứa desired action và captured session/object/generation/auth expectations để revalidate; enqueue không tăng generation, không revoke owner đang chạy và không bắt đầu deadline.

Ngoài logical owner trên, API có thể mutate **shared SDK object trước khi Promise settle** phải có `underlyingMutationLease` key theo exact object identity: `transport:<clientRef>` cho `join/leave`, `device:<trackRef>` cho `setDevice`, và `renew:<clientRef>` cho `renewToken`. Chọn strategy bắt buộc cho cả ba API này là **serialize/block**: operation đang gọi SDK giữ lease cho đến khi Promise thực sự settle và compensation/reconciliation (nếu có) hoàn tất; timeout 15 giây chỉ revoke logical ownership và chuyển UI `FAILED`, không release lease. Retry được ghi thành latest FIFO `QueuedIntent` nhưng tuyệt đối chưa phải “new generation”/operation và chưa được gọi underlying API trên cùng object. Resource/client/track không được transfer sang generation mới trong lúc lease cũ còn giữ; vì vậy side effect xảy ra trước settle vẫn thuộc operation cũ, không phải mutation của resource do generation mới sở hữu. Sau acquire, queue phải recheck cancellation tombstone, terminal/leave latch, active recovered session, authorization, exact client/track object identity, captured cleanup/client/media generation expectations và desired intent còn latest. Intent fail bất kỳ check nào bị drop/tombstone idempotently, không tạo operation identity/timer và không gọi SDK. Intent pass mới tạo operation/current owner/generation một lần, set `startedAt`/`deadlineAt` một lần, arm đúng một timer 15 giây và gọi `isCurrentOwner(op)` ngay trước SDK call.

Callback/Promise chỉ được mutate UI/current state, resolve/reject state holder, hoặc publish/transfer resource khi `isCurrentOwner(op)` đồng thời xác nhận:

1. `operationId` đúng `currentOwner[scope]` và `ownershipState=CURRENT`;
2. captured `mediaGeneration`, `cleanupGeneration` và `clientGeneration` bằng các generation hiện hành;
3. captured `sessionId` bằng authenticated active recovered session;
4. backend session chưa terminal và không có terminal notification thắng trước;
5. `monotonicNow() < deadlineAt` (strictly before boundary), operation chưa timeout;
6. operation chưa `CANCELLED`/`REVOKED`;
7. authorization cần cho operation vẫn hợp lệ (participant/session binding; token response còn đúng session/channel/UID/client).

Thiếu bất kỳ điều kiện nào thì completion là **stale**. Stale completion không được clear timer, settle UI Promise/state holder, mutate retry budget/state/resource của operation hiện hành, hoặc phát lifecycle/media-success event.

Mọi completion hợp lệ cũng phải terminalize logical owner trước khi commit: success CAS `CURRENT -> SUCCEEDED`, failure CAS `CURRENT -> FAILED`, rồi remove `currentOwner[scope]` bằng compare-ID và clear đúng timer của operation đó trước khi publish result hoặc apply current failure. CAS fail chuyển completion sang stale path. Không operation đã settle nào được để lại ở `CURRENT`, và callback không được dùng đường commit/failure riêng bỏ qua các helper normative này.

#### 6.4.2. Deadline, retry và invalidation

Deadline chuẩn của mỗi operation trong bảng 6.4.4 là **15 giây**, với interval hợp lệ half-open `[startedAt, deadlineAt)` và `deadlineAt = startedAt + 15_000ms` theo monotonic clock. Operation hết hạn **ngay tại instant `monotonicNow() >= deadlineAt`**, không phụ thuộc timer task hay Promise continuation được scheduler chạy trước. Cả timer callback lẫn completion path phải gọi cùng helper `expireAtBoundary(op, monotonicNow())` trước mọi success/failure mutation; helper atomically CAS `CURRENT -> TIMED_OUT`, xóa `currentOwner[scope]` nếu và chỉ nếu ID vẫn khớp, revoke ownership, finalize/clear timer của chính ID đó idempotently và apply timeout UI đúng generation. Vì vậy resolve/reject đúng tại `deadlineAt` luôn là stale completion/timeout, tuyệt đối không phải success/failure trước deadline, kể cả completion microtask được chạy trước timer callback. Timer callback chạy sau đó chỉ là idempotent no-op. UI có thể chuyển current operation sang application `FAILED` theo state machine; timeout không tự finalize backend Lifecycle V2 session, không gọi domain command và không tạo session/presence.

Deadline không khẳng định JavaScript Promise hoặc Agora SDK call đã bị hủy. Nếu API không hỗ trợ cancellation, underlying work được phép hoàn tất nhưng bắt buộc đi qua stale-completion rules. Retry/user recovery trực tiếp tạo `operationId`/`mediaGeneration` mới khi bắt đầu; nếu phải chờ shared lease thì trước hết chỉ enqueue intent và chỉ tạo ID/generation mới sau acquire/revalidation. Callback cũ không được clear deadline mới, settle Promise mới, tiêu thụ retry budget mới hoặc lấy lại ownership.

Riêng shared-mutation lease, timeout không cho phép “bỏ qua callback rồi gọi operation mới”: queued retry chỉ là `QueuedIntent`, không có logical operation ID/timer/state holder của active operation; SDK call mới chỉ có thể bắt đầu sau `settle -> stale compensation/reconciliation -> release lease`. Thời gian queue chờ dù dài hơn 15 giây không arm, không consume và không timeout runtime deadline. Sau acquire/revalidation, operation identity và đúng một timer được tạo; 15 giây được tính từ `startedAt` này. UI vẫn giữ `FAILED`/retry-pending trong thời gian chờ. Không có lease stealing hoặc force-release vì timeout, và không có đường code thứ hai được phép tạo lại identity/timer sau acquire.

Explicit leave, backend terminal notification/`409`, reload cleanup, recovery sang session khác, Agora client replacement, logout và component/page disposal đều phải atomically tăng `cleanupGeneration`, revoke mọi `CURRENT` operation, clear timer theo đúng captured ID, rồi serialized cleanup. Session/client replacement còn tăng generation tương ứng. Sau invalidation, callback muộn chỉ được stale cleanup tài nguyên riêng do nó tạo; không join/rejoin/publish lại. Backend terminal luôn thắng mọi callback Agora và chỉ cho RTC đi `LEAVING -> IDLE`.

Invalidation đồng thời set irreversible `terminalOrLeaveLatch` cho call generation và tombstone/hủy idempotently mọi queued intent chưa acquire lease. Intent đã tombstone vẫn có thể được queue infrastructure dequeue về mặt cơ học, nhưng bắt buộc bị drop tại revalidation mà không tạo operation/timer và không gọi SDK. Nếu singleton client đang có `join()` pending, cleanup không gọi `leave()` song song: nó đứng đầu queue sau pending call; khi stale join settle, cùng lease phải gọi `leave()` ngay (nếu client không `DISCONNECTED`) trước khi release, rồi cleanup kết thúc. Không queued retry/rejoin nào được bắt đầu khi latch bật. Như vậy terminal/leave có thể không cancel được SDK join đang chạy, nhưng membership muộn luôn bị serialized leave bù trừ và không thể trở thành current membership hoặc kích hoạt rejoin.

#### 6.4.3. Late completion và resource ownership

Late success sau timeout/cancel/revoke không được đổi `FAILED -> CONNECTED`, ghi đè client/track/device/subscription/token state, publish vào state hiện hành, tạo session/presence hoặc phát lifecycle success. Nếu result tạo resource riêng (local mic/camera/replacement track, joined client handle hoặc subscription side effect), operation gắn resource đó với `{operationId, mediaGeneration, clientGeneration}` và cleanup theo API phù hợp đúng một lần. Cleanup chỉ được tác động resource mà stale operation chứng minh nó tạo/sở hữu; tuyệt đối không stop/close/unpublish/leave resource hoặc client thuộc generation mới. Late publish phải unpublish đúng stale-owned track khỏi đúng stale client khi API cho phép; late subscribe phải stop/clear đúng stale subscription render/reference. Với result không tạo resource độc lập, token fetch payload bị discard; còn shared mutable `join`/`setDevice`/`renewToken` bắt buộc hoàn tất compensation/reconciliation dưới lease trước khi discard completion và release lease. Cleanup failure chỉ ghi sanitized diagnostic và không khôi phục operation.

Shared mutation settlement được chốt như sau. Với `join`, `setDevice` và `renewToken`, **cả Promise resolve lẫn Promise reject đều không chứng minh SDK object không bị mutate**. Vì vậy mọi settlement, kể cả current-owner failure trước deadline, timeout/stale failure và thrown/rejected error, bắt buộc chạy `postSettlementReconcile(context)` dưới chính exact-object lease trước khi release lease, bắt đầu intent kế tiếp hoặc transfer ownership. Logical result được terminalize trước: current resolve chỉ được gọi helper riêng `stageSharedMutationSuccess(op, result, exactObjectRef)` để CAS owner và lưu staged result/commit eligibility; helper này **không có đường publish hoặc transfer**. `completeOrdinarySuccess(...)` là helper khác, chỉ dành cho operation không mutate shared SDK object và có thể commit trực tiếp. Current reject trước deadline đi qua `completeFailure` và áp failure UI/retry policy; timeout/stale reject chỉ ghi diagnostic sanitized và không mutate UI. Sau đó reconciliation luôn chạy, độc lập với logical success/failure. Staged success chỉ được atomically publish/transfer exact staged object sau marker `SAFE_VERIFIED` và recheck authorization/current desired session/object/generations; nếu không còn current thì discard. Nếu reconcile fail hoặc throw thì staged result bị discard và object bị quarantine/retire. Không nhánh `catch`, `finally` hay cancellation nào được bypass reconciliation; không dùng optional/defer flag để lựa chọn semantics.

`postSettlementReconcile` phải inspect **actual state của exact SDK object sau settlement**, không suy ra state từ resolve/reject. Desired state được snapshot lại từ terminal/leave latch và newest authorized intent tại thời điểm reconcile, không dùng desired state cũ đã capture mù:

- Mỗi settlement key `{leaseKey, operationId, settlementOrdinal}` phải atomically ghi đúng một trong hai terminal marker loại trừ nhau: `SAFE_VERIFIED` hoặc `QUARANTINED_RETIRED`. Chỉ sau khi một marker đã persist trong memory registry mới được release lease; duplicate settlement/reconcile đọc lại cùng marker và không lặp side effect.
- Nếu reconcile đạt deterministic safe state, atomically ghi `SAFE_VERIFIED`; chỉ marker này mới cho staged success đi tiếp tới authorization/current-desired-state recheck và có khả năng publish/transfer.
- Nếu inspect hoặc reconcile fail, chỉ ghi sanitized diagnostic; exact object bị quarantine, serialized retire/close/leave idempotently, rồi atomically ghi `QUARANTINED_RETIRED` sau khi object đã non-callable/non-transferable. Nhánh này **không bao giờ** ghi `SAFE_VERIFIED` hoặc gọi `markReconciled`; staged result bị discard, lease chỉ release sau marker quarantine-retired, và intent kế tiếp chỉ được rebind sang replacement object identity đã verify safe. Không có đường “best effort rồi release unsafe object”.
- Terminal/leave luôn chọn desired state terminal: transport `DISCONNECTED`, track không còn usable/current thì retired/closed theo owner, renewal client retired/left. Latest retry chỉ được chạy sau safe-state marker; terminal/leave không cho retry thắng reconciliation.

Shared mutation resolution theo loại:

- `join`: sau resolve **hoặc reject**, inspect `connectionState`/membership của leased client. Nếu terminal/leave hoặc operation không phải accepted current success, desired state là `DISCONNECTED`; nếu actual state khác, `await leave()` trên chính client và verify `DISCONNECTED` trước release. Nếu accepted current success, verify actual membership đúng current session/channel/UID; mismatch phải leave rồi quarantine/retire thay vì publish `CONNECTED`. Retry chỉ acquire sau old membership đã leave và safe marker tồn tại.
- `setDevice`: sau resolve **hoặc reject**, inspect actual selected device/track state nếu SDK/browser cho phép; đồng thời re-enumerate latest authorized desired device. Nếu track vẫn là current exact track và desired intent hợp lệ, reconcile actual device về latest desired device dưới lease rồi verify; không rollback mù về captured old device. Nếu state không observable/verify được, terminal/leave thắng, track ended/replaced, hoặc reconcile fail, retire/close đúng old owned track và dùng verified replacement; không transfer exact track không an toàn. Selection/UI chỉ commit từ current successful intent sau verified actual state.
- `renewToken`: Agora không cung cấp token readback đáng tin cậy, nên reject sau khi SDK có thể mutate không được xem là verifiable. Accepted current resolve có thể giữ client chỉ khi session/client generation vẫn current. Với reject, timeout/cancel/revoke, terminal/leave, session/client replacement, hoặc current resolve mất ownership trước reconcile, exact client bắt buộc bị marked `TOKEN_STATE_UNKNOWN`, retired/left và không bao giờ transfer; replacement client generation phải fetch token mới cho đúng active session rồi join theo serialized transport contract. Newest renewal intent không được chạy trên unknown client. Như vậy stale token không thể trở thành token của current client dù SDK đã mutate rồi reject.

Late failure sau timeout/cancel/revoke không đổi UI/current error, không reject Promise mới, không tiêu thụ retry budget generation mới và không trigger lifecycle transition. Diagnostic duy nhất được phép gồm `operationType`, safe exact error code/category, `stale=true` và timestamp/opaque operation correlation; không log raw message/stack/data, token, channel, credential hoặc sensitive device label.

Token có concurrency guard riêng: token fetch/renew capture `sessionId`, `mediaGeneration`, `clientGeneration`, `operationId` và exact client reference. Response cũ không được dùng cho session/client generation mới. Backend `409` revoke recovery ownership, tăng cleanup generation và đi theo terminal contract section 7.1. `renewToken()` hoàn tất sau client replacement chỉ được coi là stale result của old client; không đánh dấu client mới renewed và không gọi renew lần nữa. Token response tự nó không có resource để cleanup nên bị discard, không log token.

#### 6.4.4. Operation ownership matrix

| Operation | Deadline/cancellation trigger | Ownership revocation | Late success action | Late failure action | Resource cleanup | UI effect | Lifecycle effect |
|---|---|---|---|---|---|---|---|
| Create microphone/camera track | 15s; leave/terminal/retry/dispose/session change | CAS owner scope `mic`/`camera` sang `TIMED_OUT/CANCELLED/REVOKED` | Không publish/store; stale-clean track | Sanitized stale diagnostic only | `stop()+close()` đúng track vừa tạo, đúng một lần; không close current track | Giữ state hiện hành; timeout current mic => blocking `FAILED`, camera => audio-only/failed policy | Zero mutation |
| Join | 15s chỉ sau lease acquisition/revalidation; queued intent chưa có deadline; leave/terminal/retry/client replacement | Revoke logical transport owner; giữ `transport:<clientRef>` lease đến settle + post-settlement inspect/reconcile; chỉ release sau terminal marker | Stage resolve, không đặt `CONNECTED`/transfer trước `SAFE_VERIFIED`; sau marker vẫn phải recheck current desired state | Current reject trước deadline terminalize/apply failure rồi reconcile; stale reject diagnostic only; cả hai inspect actual membership trước release | Verify đúng current membership; fail => retire rồi `QUARANTINED_RETIRED`, không safe marker/transfer; retry dùng replacement identity | Timeout current => `FAILED`; retry-pending chờ terminal marker; late callback không hồi sinh | Không backend join/leave/session/presence |
| Publish | 15s; unpublish/replace/leave/terminal/new generation | Revoke local-kind publish owner | Không mark published/store track; undo đúng stale publication nếu có | Diagnostic only | Unpublish đúng stale-owned track trên đúng stale client; rồi close nếu operation sở hữu track; không chạm current track | Giữ current media/failed state | Zero mutation |
| Subscribe | 15s; user-unpublished/left, leave/terminal/new generation | Revoke per-UID/media owner | Không play/render/store; discard hoặc undo stale subscription | Diagnostic only | Stop/clear đúng stale render/reference; không close SDK-owned remote track và không clear current subscription | Không thêm duplicate remote media | Zero mutation |
| Device switch | 15s chỉ sau exact-track lease acquisition/revalidation; queued intent chưa có deadline; switch khác/hot-plug/leave/terminal/new generation | Revoke logical device owner; `setDevice` giữ `device:<trackRef>` lease đến settle + inspect/reconcile; chỉ release sau terminal marker | Stage selection/result; không commit/transfer trước `SAFE_VERIFIED` và current desired-state recheck | Current reject trước deadline apply failure rồi reconcile; stale reject diagnostic only; không release chỉ vì reject | Reconcile/verify exact track; fail => close/retire rồi `QUARANTINED_RETIRED`, không safe marker; replacement identity riêng, không close current replacement | Giữ `FAILED`/degraded hoặc latest pending selection đến verified current success | Zero mutation |
| Token fetch/renew | Fetch không lease: 15s từ operation start; renew: 15s chỉ sau exact-client lease acquisition/revalidation, queued intent chưa có deadline; newer renewal/did-expire/leave/terminal/session/client change | Revoke logical token owner; `renewToken` giữ `renew:<clientRef>` lease xuyên settlement reconciliation; chỉ release sau terminal marker; `409` invalidate recovery | Fetch payload discard nếu stale; renew resolve chỉ stage, rồi `SAFE_VERIFIED` + current session/client recheck mới commit renewed marker | Current reject apply failure rồi mark token state unknown; stale reject sanitized diagnostic; cả hai retire/isolate exact client trước release | Token fetch discard; unsafe renew => retire/leave rồi `QUARANTINED_RETIRED`, không safe marker/transfer; replacement fetches fresh token | Giữ current renewal/RTC failure state; current `409` theo terminal UX | Zero mutation; backend terminal authoritative |
| Transport recovery/did-expire rejoin | 15s chỉ từ lúc từng shared SDK operation acquire lease/revalidate/bắt đầu; queued intent chưa có deadline; explicit leave/terminal/retry/session/client change | Revoke serialized recovery owner; tombstone queued intent khi invalidated | Không republish/set connected; stale cleanup đúng client/tracks operation sở hữu | Diagnostic only | Leave/undo only stale client/publication; retained current-owned tracks không close | Timeout current => `FAILED`; queue wait không consume deadline; late completion không recover UI | Zero backend join/leave/presence |
| Explicit cleanup/leave | Bắt đầu ngay khi event authoritative; disposal có bounded cleanup nhưng không nhường ownership lại | Tăng cleanup generation, revoke **tất cả** open owners trước side effect | Duplicate/late completion chỉ join cleanup Promise hiện hành; không rejoin | Diagnostic only; cleanup tiếp tục | Cleanup each owned resource once; identity check trước close/leave | `LEAVING -> IDLE` hoặc terminal UI | Chỉ explicit existing V2 workflow; cleanup callback không tạo transition |

#### 6.4.5. Normative algorithm

```text
startOperationAfterPreconditions(type, scope, session, client, startsNewGeneration):
  if startsNewGeneration: mediaGeneration += 1
  old = currentOwner[scope]
  if old is CURRENT: revoke(old, REVOKED)       // old can never become current again
  op = immutable {
    operationId = uniqueId(), operationType = type, scope,
    mediaGeneration, cleanupGeneration, clientGeneration,
    sessionId = session.id, clientRef = client,
    startedAt = monotonicNow(), deadlineAt = startedAt + 15_000,
    ownershipState = CURRENT
  }
  currentOwner[scope] = op
  op.timer = scheduleFor(op.operationId, 15_000, () => onDeadline(op))
  return op                                      // callbacks capture this exact object/ID

enqueueSharedIntent(type, scope, session, exactObject, desiredAction, expected):
  intent = immutable {
    intentId = uniqueId(), type, scope, sessionId = session.id,
    exactObjectRef = exactObject, desiredAction,
    expectedMediaGeneration, expectedCleanupGeneration, expectedClientGeneration,
    cancellationTombstone = false
  }
  enqueueFIFO(keyFor(type, exactObject), intent)
  // intent is NOT an operation: no operationId/owner/startedAt/deadlineAt/timer
  return intent

cancelQueuedIntent(intent):
  intent.cancellationTombstone = true             // idempotent; dequeue will drop it

isCurrentOwner(op):
  return op.ownershipState == CURRENT
    && currentOwner[op.scope]?.operationId == op.operationId
    && op.mediaGeneration == mediaGeneration
    && op.cleanupGeneration == cleanupGeneration
    && op.clientGeneration == clientGeneration
    && op.sessionId == authenticatedActiveSession?.id
    && authenticatedActiveSession is authorized and nonterminal
    && monotonicNow() < op.deadlineAt

expireAtBoundary(op, now):
  if now < op.deadlineAt: return false
  if atomicTransition(op, CURRENT, TIMED_OUT):
    delete currentOwner[op.scope] only if ID still equals op.operationId
    clearTimerOwnedBy(op.operationId)
    apply FAILED/degraded UI for op only if call/session generations still match
    emit no Lifecycle V2 command
  return true                                      // expired even if another path already revoked it

onDeadline(op):
  expireAtBoundary(op, monotonicNow())             // cannot clear a later operation timer

completeOrdinarySuccess(op, result, resourcesCreatedByOp):
  // Only for non-shared operations; this helper always commits directly.
  expireAtBoundary(op, monotonicNow())             // boundary wins before any mutation
  if !isCurrentOwner(op):
    cleanupExactlyOnce(resourcesCreatedByOp, onlyWhenOwnerIdentityEquals(op))
    discard(result)
    recordSanitized(op.operationType, stale=true, cleanupOutcome)
    return false
  if !atomicTransition(op, CURRENT, SUCCEEDED):
    cleanupExactlyOnce(resourcesCreatedByOp, onlyWhenOwnerIdentityEquals(op))
    discard(result)
    recordSanitized(op.operationType, stale=true, cleanupOutcome)
    return false
  delete currentOwner[op.scope] only if ID still equals op.operationId
  clearTimerOwnedBy(op.operationId)
  publish/transfer result only to matching scope and generation
  return true

stageSharedMutationSuccess(op, result, exactObjectRef):
  // Only for JOIN/DEVICE_SWITCH/TOKEN_RENEW while exact-object lease is held.
  expireAtBoundary(op, monotonicNow())
  if !isCurrentOwner(op) or !atomicTransition(op, CURRENT, SUCCEEDED):
    discard(result)
    recordSanitized(op.operationType, stale=true)
    return false
  delete currentOwner[op.scope] only if ID still equals op.operationId
  clearTimerOwnedBy(op.operationId)
  stagedSuccess[op.operationId] = { result, exactObjectRef, captured identity/generations }
  // MUST NOT publish state/resource, transfer object, or emit success here.
  return true

completeFailure(op, error):
  expireAtBoundary(op, monotonicNow())             // boundary wins before any mutation
  if !isCurrentOwner(op):
    recordSanitized(op.operationType, safeCodeOrCategory(error), stale=true)
    return false                                   // no UI/retry/lifecycle mutation
  if !atomicTransition(op, CURRENT, FAILED):
    recordSanitized(op.operationType, safeCodeOrCategory(error), stale=true)
    return false
  delete currentOwner[op.scope] only if ID still equals op.operationId
  clearTimerOwnedBy(op.operationId)
  apply only this operation's failure/retry policy; emit no domain transition
  return true

onSuccess(op, result, resourcesCreatedByOp):
  return completeOrdinarySuccess(op, result, resourcesCreatedByOp)

onFailure(op, error):
  return completeFailure(op, error)

invalidateAll(reason):
  cleanupGeneration += 1
  if reason replaces session/call: mediaGeneration += 1
  if reason replaces client: clientGeneration += 1
  for each op where ownershipState == CURRENT:
    revoke(op, reason is timeout ? TIMED_OUT : REVOKED)
    clearTimerOwnedBy(op.operationId)
  serializedIdempotentCleanup(resources whose owner generation is now stale)

runSharedMutation(intent, sdkCall, newestIntent):
  leaseKey = keyFor(intent.type, intent.exactObjectRef) // transport/client, device/track, renew/client
  await acquireFIFO(leaseKey, intent.intentId)     // queue wait has no operation deadline
  if intent.cancellationTombstone
     || terminalOrLeaveLatch
     || !activeSessionAuthorizationObjectGenerationAndDesiredIntentCheck(intent):
    tombstoneAndDropWithoutCallingSdk(intent)
    releaseOnlyIfHeldBy(leaseKey, intent.intentId)
    return
  op = startOperationAfterPreconditions(
         intent.type, intent.scope, authenticatedActiveSession,
         intent.exactObjectRef, intentStartsNewGeneration(intent))
  // startOperation creates startedAt/deadlineAt and arms exactly one 15s timer.
  // No second timer/identity creation is allowed here or in sdkCall.
  if !isCurrentOwner(op):
    revoke(op, CANCELLED); clearTimerOwnedBy(op.operationId)
    releaseOnlyIfHeldBy(leaseKey, intent.intentId); return
  settlement = null
  logicalAccepted = false
  try:
    result = await sdkCall(intent.exactObjectRef)  // SDK may mutate object before settle
    settlement = { kind: RESOLVED, result, ordinal: nextSettlementOrdinalExactlyOnce(op) }
    logicalAccepted = stageSharedMutationSuccess(op, result, intent.exactObjectRef)
    // Result/commit eligibility is staged; shared object is still owned by this lease.
  catch error:
    settlement = { kind: REJECTED, safeError: safeCodeOrCategory(error),
                   ordinal: nextSettlementOrdinalExactlyOnce(op) }
    logicalAccepted = completeFailure(op, error)    // current pre-deadline reject applies failure first
  finally:
    clearTimerOwnedBy(op.operationId)
    // Normative for BOTH RESOLVED and REJECTED. Never infer unchanged object from reject.
    try:
      reconciliation = await postSettlementReconcile({
        op, settlement, logicalAccepted,
        exactObjectRef: intent.exactObjectRef,
        desired: recomputeLatestAuthorizedDesiredState(newestIntent, terminalOrLeaveLatch)
      })
    catch reconcileError:
      recordSanitized(op.operationType, safeCodeOrCategory(reconcileError), stale=!logicalAccepted)
      reconciliation = { kind: QUARANTINED, exactObjectRef: intent.exactObjectRef }
    if reconciliation.kind == QUARANTINED:
      atomicallyMarkObjectQuarantinedNonCallableNonTransferable(intent.exactObjectRef, op.operationId)
      await retireOrReplaceExactlyOnce(intent.exactObjectRef, ownerIdentity=op)
      discardStagedSuccess(op)
      atomicallyRecordTerminalMarkerExactlyOnce(
        leaseKey, op.operationId, settlement.ordinal, QUARANTINED_RETIRED)
      // Never record SAFE_VERIFIED/markReconciled on this path. Replacement has a new identity.
    else:
      atomicallyRecordTerminalMarkerExactlyOnce(
        leaseKey, op.operationId, settlement.ordinal, SAFE_VERIFIED)
      // Marker is visible before any staged publish/transfer.
      if logicalAccepted
         && recheckAuthorizationSessionObjectGenerationsAndLatestDesiredState(op, intent.exactObjectRef):
        atomicallyPublishAndTransferExactStagedResult(op, intent.exactObjectRef)
      else:
        discardStagedSuccess(op)
    if terminalOrLeaveLatch: dropAllQueuedIntentsForCall()
    assert terminalMarker is SAFE_VERIFIED or QUARANTINED_RETIRED
    assert QUARANTINED_RETIRED implies exactObject is non-callable and non-transferable
    releaseOnlyIfHeldBy(leaseKey, intent.intentId)
    dequeue next intent; repeat full pre-SDK revalidation before creating its operation

postSettlementReconcile(context):
  // Called under the same exact-object lease after every resolve/reject.
  if context.op.type is JOIN:
    inspect actual membership
    if terminal/leave or !context.logicalAccepted: leave exact client; verify DISCONNECTED
    else verify membership == current authorized session/channel/UID
  if context.op.type is DEVICE_SWITCH:
    inspect actual selected device and exact track viability
    reconcile to recomputed latest authorized desired device and verify;
    if unobservable/unverifiable, retire old-owned track and require verified replacement
  if context.op.type is TOKEN_RENEW:
    if context.settlement is REJECTED or !context.logicalAccepted:
      mark exact client TOKEN_STATE_UNKNOWN; serialized leave/retire; never transfer it
    else verify session/client generation still current before accepting renewed marker
  // Pure decision/result: this function never records a marker or releases the lease.
  return { kind: SAFE_VERIFIED, verifiedObjectRef } only after actual-object invariant holds
  return { kind: QUARANTINED, exactObjectRef, safeDiagnostic } on inspect/reconcile failure
```

Resource registry/cleanup marker phải key theo identity resource + owner operation/generation, không chỉ theo media kind. Vì vậy duplicate callback/cleanup là idempotent, trong khi track cùng kind của generation mới không thể bị cleanup nhầm.

### 6.5. Agora exception/error classification 4.24.7

Nguồn normative là `rtc-sdk_en.d.ts` của `agora-rtc-sdk-ng@4.24.7` tại URL section 4.2. Declaration nói rõ client `exception({code:number,msg:string,uid})` báo **quality issue và recovery**, không phải error. Exact exception codes là `1001`, `1002`, `1003`, `1005`, `2001`, `2002`, `2003`, `2005`; recovery tương ứng là `3001`, `3002`, `3003`, `3005`, `4001`, `4002`, `4003`, `4005`. Vì event không mang `AgoraRTCErrorCode`, không implementation nào được suy fatal từ `msg`, substring hoặc stack.

| Exact Agora code/category | Recoverable hay fatal | UI behavior | Retry behavior | Backend/lifecycle effect | Sanitized telemetry được phép |
|---|---|---|---|---|---|
| `exception` `1001/1002/1003/1005` — video input/send/decode quality | Recoverable diagnostic; **không fatal** | Non-blocking video degraded; giữ audio | Không retry operation từ event; chờ paired `3001/3002/3003/3005` hoặc network/adaptive policy | Zero mutation; không finalize | `eventCategory=AGORA_EXCEPTION_VIDEO`, numeric code, RTC state, timestamp, opaque session ID |
| `exception` `2001/2002/2003/2005` — audio input/output/send/decode quality | Recoverable diagnostic; **không fatal** | Cảnh báo audio degraded; CTA device/network nếu policy tương ứng cho phép | Không auto retry từ event; chờ paired `4001/4002/4003/4005` và actual media outcome | Zero mutation | `eventCategory=AGORA_EXCEPTION_AUDIO`, numeric code, RTC state, timestamp, opaque session ID |
| `exception` `3001/3002/3003/3005` | Recovery signal | Clear đúng video diagnostic matching code; không tự đặt RTC connected | Không retry | Zero mutation | `eventCategory=AGORA_EXCEPTION_VIDEO_RECOVERED`, numeric code |
| `exception` `4001/4002/4003/4005` | Recovery signal | Clear đúng audio diagnostic matching code sau actual media outcome; không fake playback success | Không retry | Zero mutation | `eventCategory=AGORA_EXCEPTION_AUDIO_RECOVERED`, numeric code |
| `exception` numeric code khác/unknown future | Không xác định; event chỉ diagnostic, **không fatal** | Generic degraded notice, không render raw `msg` | Chỉ retry nếu owning operation policy riêng cho phép; event tự nó không retry | Zero mutation; không tự finalise | `eventCategory=AGORA_EXCEPTION_UNKNOWN`, numeric code nếu finite integer; không `msg`/uid raw |
| Operation `AgoraRTCErrorCode.PERMISSION_DENIED`, `DEVICE_NOT_FOUND`, `ENUMERATE_DEVICES_FAILED`, `NOT_READABLE`, `NOT_SUPPORTED`, `WEB_SECURITY_RESTRICT`, `CONSTRAINT_NOT_SATISFIED` | Non-retryable cho operation hiện tại; device may recover bằng user gesture/hot-plug | Map lần lượt vào taxonomy section 8: denied; missing/enumeration; busy/I-O (`NOT_READABLE`); unsupported/security/constraint | Không auto retry; user gesture hoặc device-change mới mở operation mới | Zero mutation | Category `DEVICE_PERMISSION`, `DEVICE_MISSING`, `DEVICE_BUSY_IO` hoặc `UNSUPPORTED` + exact enum code |
| Join/auth/token `TOKEN_EXPIRE`, `UID_CONFLICT`, `INVALID_PARAMS`, `INVALID_UINT_UID_FROM_STRING_UID`, `UPDATE_TICKET_FAILED`, `CAN_NOT_GET_GATEWAY_SERVER`, `VOID_GATEWAY_ADDRESS` | Fatal cho **current operation**, không tự kết luận domain terminal | Token-expire dùng 7.4; conflict/invalid/config hiển thị generic recovery failed | Token chỉ retry qua backend policy; conflict/invalid không auto retry | Zero mutation; backend `409`/terminal mới terminal domain | `eventCategory=JOIN_AUTH_TOKEN`, exact enum code, operation name |
| Network/transient `NETWORK_ERROR`, `NETWORK_TIMEOUT`, `NETWORK_RESPONSE_ERROR`, `API_INVOKE_TIMEOUT`, `TIMEOUT`, `WS_ABORT`, `WS_DISCONNECT`, `WS_ERR`, `ICE_FAILED`, `NO_ICE_CANDIDATE`, `GATEWAY_P2P_LOST` | Recoverable trong budget của operation | Reconnecting/disconnected theo 6.3.1 | Chỉ retry theo exact owning token/join/recovery budget; không có retry ngầm | Zero mutation | `eventCategory=NETWORK_TRANSIENT`, exact enum code, attempt ordinal |
| Publish/subscribe/media `INVALID_LOCAL_TRACK`, `INVALID_TRACK`, `TRACK_IS_DISABLED`, `TRACK_STATE_UNREACHABLE`, `SENDER_NOT_FOUND`, `SENDER_REPLACE_FAILED`, `SUBSCRIBE_FAILED`, `UNSUBSCRIBE_FAILED`, `REMOTE_USER_IS_NOT_PUBLISHED`, `INVALID_REMOTE_USER`, `CAN_NOT_PUBLISH_MULTIPLE_VIDEO_TRACKS`, `LOW_STREAM_ENCODING_ERROR`, `SET_ENCODING_PARAMETER_ERROR` | Recoverable nếu device/track/replacement policy còn budget; otherwise fatal cho current media operation | Mic blocking, camera audio-only hoặc remote-media degraded theo kind | Section 9 rollback/replacement hoặc one explicit retry; không loop | Zero mutation | `eventCategory=MEDIA_TRACK_OPERATION`, exact enum code, media kind; không device label |
| Internal/unknown `UNEXPECTED_ERROR`, `UNEXPECTED_RESPONSE`, `PB_ERROR` hoặc enum/code không nằm mapping trên | Không xác định; fatal cho current operation khi state không xác định hoặc retry budget hết | Generic `recovery failed`; không render raw message | Chỉ retry nếu owning operation policy cho phép; nếu không/hết budget thì dừng media recovery, chờ backend lifecycle hoặc explicit user action | Zero mutation; không tự finalise | `eventCategory=AGORA_INTERNAL_UNKNOWN`, exact safe code/category nếu có; không raw message/stack/data |

“Fatal” trong bảng chỉ có nghĩa current RTC/media operation dừng và application có thể vào `FAILED`; nó **không** có nghĩa domain terminal. Raw `message`, `msg`, `data`, token, channel, UID, credential và stack không được log. Mọi classification dùng exact numeric code hoặc exact `AgoraRTCErrorCode`; không dùng nội dung message. `exception` event một mình không bao giờ là terminal decision.

## 7. Token contract và recovery

### 7.1. Endpoint authoritative

Frontend chỉ lấy token bằng authenticated endpoint:

```http
GET /api/sessions/{sessionId}/token
```

Response giữ backend-owned `token`, `channelName`, `uid`. Client không nhận input cho channel/UID và không thay đổi UID giữa renew/rejoin của cùng session.

- `401/403`: fatal auth UX, dừng media recovery, cleanup RTC; không tạo session/presence.
- `404`: fatal invalid/missing session UX, cleanup RTC.
- `409`: backend terminal/expired lifecycle; tăng generation, dừng media recovery, cleanup media và chờ/mirror explicit backend terminal/active-session response; không tự infer domain terminal từ Agora state và không gọi join/presence creation.
- Token không được log, render, lưu local/session storage, telemetry hoặc error payload.

### 7.2. Retry đã chốt

- Một token fetch logical operation có tối đa **3 attempts tổng cộng**.
- Delay exponential trước retry 2 và 3 lần lượt là **500 ms** và **1,000 ms**, cộng jitter ngẫu nhiên `0..200 ms`.
- Chỉ retry network error, `408`, `429` và `5xx`.
- Không retry `401`, `403`, `404`, `409` hoặc parse/schema error.
- Timer retry phải generation-aware và cancel khi leave/terminal/new generation.

### 7.3. Will-expire

`token-privilege-will-expire`:

1. Nếu không `CONNECTED/RECONNECTING`, bỏ qua an toàn.
2. Lấy hoặc reuse token-refresh single-flight Promise cho session/generation hiện tại.
3. Fetch token theo policy retry.
4. Recheck generation, session, channel và UID.
5. Enqueue `QueuedIntent` không operation identity/timer; sau khi acquire FIFO `renew:<exact clientRef>` lease, recheck cancellation/terminal/session/auth/exact client/generations/desired renewal. Chỉ intent còn valid mới tạo renew operation identity một lần, arm đúng một timer 15 giây và gọi `isCurrentOwner` ngay trước `await client.renewToken(newToken)`; lease giữ xuyên qua timeout đến Promise settle.
6. Nếu timeout, UI giữ `FAILED`/degraded nhưng renewal mới trên cùng client chỉ được queue/coalesce, không gọi chồng lấn. Late settle discard logical result; newest queued intent phải fetch/recheck token hiện hành trước khi chạy. Terminal/leave/client replacement drop queue và retire/leave leased client, không transfer nó sang generation mới.
7. Với intent đã thành operation, xóa single-flight logical Promise/timer theo đúng operation ID trong `finally`; intent bị tombstone/drop không có timer để xóa. Failure hiển thị degraded/retry UX nhưng không domain-leave.

Các will-expire event đồng thời chỉ tạo một HTTP request chain và một `renewToken`.

### 7.4. Did-expire

Theo signature 4.24.7, token đã expire yêu cầu token mới và `join`, không dùng `renewToken` cho recovery này.

1. Coalesce event vào single-flight recovery của generation hiện tại.
2. Serialize sau operation đang chạy; nếu leave/terminal đã thắng thì abort.
3. Fetch token mới theo policy retry và verify cùng session/channel/UID.
4. Giữ/recreate owned local tracks: track còn live được giữ; ended/closed track được recreate theo device policy, microphone vẫn bắt buộc.
5. Acquire FIFO `transport:<exact clientRef>` lease. Đọc exact `client.connectionState` 4.24.7. Nếu `CONNECTED`, `CONNECTING`, `RECONNECTING` hoặc `DISCONNECTING`, đợi operation hiện hành settle/compensate rồi `await client.leave()` để đạt `DISCONNECTED`; nếu đã `DISCONNECTED` thì không leave lại. Chỉ gọi `join` trực tiếp từ `DISCONNECTED`. Event/Promise trong `DISCONNECTING` không được bắt đầu join sớm. Transport leave này **không** gọi backend `/leave-agora`.
6. Recheck terminal/leave latch, session và generations ngay trước SDK call rồi `await client.join(appId, sameChannel, newToken, sameUid)` đúng một lần từ `DISCONNECTED`. Lease không được release bởi deadline; late join phải serialized leave bù trừ trước release, sau đó newest retry mới được acquire nếu latch vẫn false.
7. Sau transport leave, retained track ownership không có nghĩa track còn published. Re-publish danh sách owned usable tracks đúng một lần; không POST backend `/join-agora`; gọi lại volume/adaptive setup cần thiết sau rejoin.
8. Chỉ khi toàn bộ bước thành công và generation còn hiện hành mới đặt RTC `CONNECTED`.
9. Hết retry: RTC/media `FAILED`, hiển thị explicit retry/end CTA; domain giữ nguyên và chờ existing WS/backend lifecycle.

Join ban đầu và did-expire recovery không chạy song song. Explicit leave luôn thắng bằng generation invalidation.

## 8. Pre-call gate

Pre-call chạy sau khi backend đã allocate match/session vì UX hiện tại chỉ biết call context sau `MATCHED`; nó không enqueue lại, không tạo session/presence khác và chưa POST join-agora.

Thứ tự:

1. Trước gesture chỉ gọi synchronous `AgoraRTC.checkSystemRequirements()` và kiểm tra an toàn sự tồn tại/type của API (`getMicrophones`, `getCameras`, `getPlaybackDevices`, track factories, playback switching). Không enumerate và không create track trước gesture.
2. Trong explicit click/tap “Kiểm tra thiết bị”, gọi `getMicrophones(false)`, `getCameras(false)` và, nếu feature hiện diện, `getPlaybackDevices(false)`. Trong 4.24.7, omitted/`false` **không skip permission check** và có thể bật mic/camera ngắn để xin quyền; vì vậy mọi call này bắt buộc nằm trong gesture. `true` chỉ được dùng cho later refresh sau khi đã có consent, chấp nhận danh sách có thể không chính xác; không dùng `true` để tuyên bố device available trước consent.
3. Vẫn trong gesture, tạo microphone preview track và mic meter; microphone usable là gate bắt buộc.
4. Tạo camera preview nếu có/được phép; camera failure vẫn cho audio-only.
5. Sau consent mới render label mic/camera/speaker. Khi chưa consent hoặc permission absent, UI chỉ dùng generic kind/index, không hứa label; permission denied map `PERMISSION_DENIED`, empty list map `DEVICE_MISSING`, và không tự retry ngoài gesture.
6. Preview tracks chuyển ownership trực tiếp sang call join và được reuse/publish, không tạo duplicate call tracks. Cancel, reload, terminal hoặc stale generation stop+close từng preview track đúng một lần.
7. Speaker selector chỉ hiện khi remote audio playback switching được feature-detect; chỉ enable “Join media call” khi microphone preview usable.

Error taxonomy bắt buộc:

- `PERMISSION_DENIED`
- `DEVICE_MISSING`
- `DEVICE_BUSY`
- `UNSUPPORTED`
- `DEVICE_IO_ERROR`

Microphone thiếu/bị từ chối/busy => blocking state; không enter Agora media call và không POST join-agora. Camera tương tự => audio-only allowed. Cancel pre-call gửi existing authoritative cancel/end command phù hợp trạng thái `MATCHED`, cleanup preview, không tự tạo giả `leave-agora`; backend quyết định rollback/finalization theo V2. Reload/recovery vẫn resolve cùng allocated session, không allocate session mới.

## 9. Device selection và hot-plug recovery

Đăng ký đúng một lần trên module `AgoraRTC`:

- `camera-changed`
- `microphone-changed`
- `playback-device-changed`

Policy deterministic:

1. Giữ selected device nếu deviceId còn tồn tại.
2. Nếu mất, chọn device đầu tiên theo thứ tự trả về đã normalize ổn định theo `deviceId`; không dựa vào label trước consent.
3. Với live same-kind local track, lựa chọn ưu tiên là FIFO serialized `await localTrack.setDevice(deviceId)` dưới `device:<exact trackRef>` lease (`ILocalAudioTrack.setDevice(string)` hoặc `ILocalVideoTrack.setDevice(string|device constraint)` trong 4.24.7). Lease giữ đến settle kể cả sau deadline; track chưa transfer cho device generation mới. Success chỉ cập nhật selection nếu logical owner còn hiện hành.
4. Nếu `setDevice` fail hoặc track ended: create và verify replacement; nếu old đang published, `unpublish(old)` trước rồi `publish(replacement)`; không có thời điểm publish hai track cùng kind.
5. Chỉ sau replacement publish success mới transfer ownership và stop+close old. Nếu publish replacement fail, stop+close replacement và cố rollback bằng republish old nếu old còn usable. Rollback fail => mic blocking degraded hoặc camera audio-only.
6. Temporary mic gap trong unpublish/publish được phép nhưng UI phải hiện blocking degraded; không gửi backend/domain mutation.
7. Completion stale theo generation phải stop+close track riêng nó vừa tạo và không đổi selection/UI. Với stale same-track `setDevice`, không gọi switch mới song song và không rollback về captured device: giữ UI degraded/pending, coalesce newest desired device, rồi sau old settle re-enumerate/validate và chạy newest switch dưới cùng lease. Nếu leave/terminal/track replacement thắng thì drop queue; cleanup track theo resource owner.

- Mic lost: blocking degraded state, mute speaking controls, retry enumerate/create theo user gesture; domain không đổi.
- Camera lost: unpublish/close video an toàn và degrade audio-only; mic/call tiếp tục.
- Playback lost: chọn fallback deterministic và gọi remote audio track `setPlaybackDevice` nếu browser hỗ trợ; nếu không, dùng default browser output và hiển thị non-blocking notice.
- Playback selection được feature-detect; Web SDK 4.24.7 ghi rõ `IRemoteAudioTrack.setPlaybackDevice` chỉ hỗ trợ Chrome/Edge desktop, lỗi `NOT_SUPPORTED` phải degrade gracefully.
- Không reload trang, không rematch, không tạo session/presence trong device recovery.

## 10. Autoplay và AudioContext

- Handle cả global `autoplay-failed` và failure/outcome từ remote `audioTrack.play()`/`videoTrack.play()` theo contract runtime.
- Remote audio blocked là critical: hiển thị CTA accessible có focus, keyboard activation và screen-reader text “Bật âm thanh”. Video-only blocked là non-blocking visual retry; không chặn speaking call.
- Chỉ trong user gesture của CTA: gọi `AgoraRTC.resumeAudioContext()` nếu method tồn tại. Signature 4.24.7 trả `void`, vì vậy **không `await`** method này; sau đó retry play trên remote tracks hiện còn published.
- `audio-context-state-changed` sang `interrupted` chỉ đánh dấu blocked và mở CTA; không auto-resume ngoài gesture.
- Mỗi gesture thực hiện tối đa **2 play attempts**, cách nhau **250 ms**; sau resume/retry quan sát tối đa **3,000 ms** cho `audio-context-state-changed=running` và actual audio play success. Timeout/failure giữ CTA và error taxonomy, không fake PASS. Video-only retry cũng tối đa 2 attempts/250 ms và giữ non-blocking visual nếu fail.
- Chỉ actual audio play success (và AudioContext `running` khi browser phát event này) mới được coi audio recovery PASS.
- Browser/environment không tái tạo được autoplay block phải ghi manual `BLOCKED` hoặc `NOT RUN`, không suy luận PASS.
- Autoplay/audio-context event không đổi domain state.

## 11. Adaptive media policy — P1 chỉ sau full `P0_GATE` PASS

Không bắt đầu phần này trước khi toàn bộ `P0_GATE` tại section 17 PASS; các P0 test riêng lẻ không đủ mở gate.

### 11.1. API/signature 4.24.7

- Sau khi publish local video: `await client.enableDualStream()`.
- Remote default high: `await client.setRemoteVideoStreamType(uid, 0)` (`RemoteStreamType.HIGH_STREAM`).
- Degraded: `await client.setRemoteVideoStreamType(uid, 1)` (`LOW_STREAM`).
- Fallback: `await client.setStreamFallbackOption(uid, 2)` (`RemoteStreamFallbackType.AUDIO_ONLY`: low video khi xấu và audio-only khi quá xấu).
- Listen `stream-type-changed(uid, streamType)` và `stream-fallback(uid, "fallback" | "recover")`.
- `enableDualStream` có trong 4.24.7 nhưng Agora dự kiến deprecate dần; task này dùng đúng API user yêu cầu và pin version nên signature ổn định.

### 11.2. Threshold và chống oscillation

- `network-quality` tự phát mỗi 2 giây sau join; không tạo polling riêng.
- Degrade khi có **3 mẫu liên tiếp** `downlinkNetworkQuality >= 4`, hoặc RTC connection đang `RECONNECTING`.
- Recover high khi có **5 mẫu liên tiếp** `downlinkNetworkQuality <= 2`, RTC `CONNECTED`, media reconnect không active.
- Mẫu `0/UNKNOWN` reset cả hai counter; mẫu `3` reset recover counter nhưng không tự degrade.
- Tối thiểu **10 giây** giữa hai lần `setRemoteVideoStreamType`; reconnect có thể yêu cầu degrade ngay nhưng vẫn không spam duplicate call.
- High là default; low khi degraded. SDK fallback audio-only được phép tự recover; UI phản ánh event thực tế, không đoán fallback từ quality.
- Mỗi remote UID có counter/switch timestamp riêng; 1-1 vẫn không dùng global mutable counter.
- Nếu capability/API/browser không support hoặc call reject: disable adaptation cho generation hiện tại, giữ media usable, hiển thị non-blocking notice, không domain change.

### 11.3. Network counter update/reset matrix

Mỗi remote UID chỉ có ba integer counter khai báo rõ: `goodConsecutive`, `degradedConsecutive`, `poorFallbackConsecutive`. Threshold đã chốt chỉ dùng `goodConsecutive=5` để high và `degradedConsecutive=3` để low. Không có threshold quality-sample cho audio-only: `poorFallbackConsecutive` luôn reset `0`, vì audio-only chỉ mirror SDK `stream-fallback`; cấm dùng counter này để tự suy fallback. Không có timer/counter ngầm ngoài event cadence 2 giây và switch timestamp 10 giây ở 11.2.

| Input | `goodConsecutive` | `degradedConsecutive` | `poorFallbackConsecutive` | Reset bắt buộc | UI state | Stream action | Debounce/hysteresis |
|---|---|---|---|---|---|---|---|
| Bucket `0` (`UNKNOWN`) | `0` | `0` | `0` | Cả ba | `networkHealth=UNKNOWN`, trừ reconnect override | Không đổi stream | Không tính sample; không cộng dồn qua sample này |
| Bucket `1-2` | `+1` từ chuỗi `1-2` hiện tại | `0` | `0` | Degraded và poor/fallback | `GOOD` khi không reconnect | Khi đạt đúng 5 và RTC `CONNECTED`, media reconnect idle: request HIGH nếu 10 giây switch guard cho phép | Chuỗi phải liên tiếp; bucket khác cắt chuỗi; counter saturate tại 5 sau trigger |
| Bucket `3` | `0` | `0` | `0` | Cả ba | `UNSTABLE` | Không đổi stream | Làm đứt cả good/degraded chain; không tích lũy |
| Bucket `4-6` | `0` | `+1` từ chuỗi `4-6` hiện tại | `0` | Good và poor/fallback | `POOR` | Khi đạt đúng 3: request LOW nếu 10 giây switch guard cho phép; audio-only chỉ do SDK fallback event | Chuỗi phải liên tiếp; bucket khác cắt chuỗi; counter saturate tại 3 sau trigger |
| Reconnect override (`rtc=RECONNECTING` hoặc media reconnect active) | `0` | `0` | `0` | Cả ba ngay khi enter | Luôn `reconnecting`, override mọi network-quality presentation | Có thể request LOW ngay một lần; duplicate coalesced; audio-only vẫn SDK-owned | Reconnect bypass threshold nhưng không bypass duplicate guard; 10 giây chỉ được bypass cho lần immediate degrade đầu tiên |
| Connection/media restored | `0` | `0` | `0` | Cả ba trước sample mới | Nhãn tính từ `CONNECTED` + `UNKNOWN` cho tới sample kế | Không tự request HIGH khi vừa restored; bắt đầu lại 5 good samples | Switch guard timestamp vẫn giữ trong cùng generation; counters không giữ |
| Session leave/cleanup | `0` | `0` | `0` | Cả ba và switch timestamp/per-UID state | Không còn network call UI | Không gọi stream API | Hủy toàn bộ debounce state |
| New session/generation initialization | `0` | `0` | `0` | Cả ba; tạo state per-UID mới, switch timestamp unset | `UNKNOWN` | Default HIGH sau remote publish theo 11.1 | Bắt đầu mới hoàn toàn; không kế thừa sample/timer |

Một sample chỉ thuộc đúng một bucket. Vì mọi row reset counter không tương thích, sample không liên tiếp tuyệt đối không được cộng dồn để kích hoạt degrade/recover. `stream-type-changed` và `stream-fallback` vẫn là nguồn UI stream/fallback thực tế; counter chỉ quyết định request, không giả lập success.

## 12. Volume indicator — P1 chỉ sau full `P0_GATE` PASS

- Sau mỗi successful join/rejoin gọi `client.enableAudioVolumeIndicator()`; SDK yêu cầu enable lại sau leave/rejoin.
- Listen `volume-indicator`; SDK báo khoảng mỗi 2 giây.
- UI renderer throttle tối đa **4 Hz** dù test/mock phát event nhanh hơn.
- Hiển thị local mic activity và remote active speaker; speaking threshold UI mặc định `level > 60` theo type docs 4.24.7.
- Dữ liệu chỉ ở memory của generation hiện tại; reset khi leave.
- Cấm DB/storage/log/telemetry per-user samples, volume history hoặc audio content.

## 13. Listener và cleanup ownership

- Agora client là singleton page-lifetime; module listeners và client listeners đăng ký đúng một lần khi module init, không đăng ký lại mỗi join/recovery. Vì singleton là shared mutable resource, mọi `join/leave` dùng cùng FIFO transport lease section 6.4; retry không được gọi `join` cho đến khi pending old join settle và stale membership đã leave bù trừ.
- Listener callbacks đọc generation/current ownership; stale callback là no-op.
- Page teardown có thể remove đúng handler reference; per-call leave không remove singleton listeners.
- `user-unpublished` phải branch theo `mediaType`: audio unpublish không clear video; video unpublish chỉ clear/stop render video liên quan.
- Không close SDK-owned remote tracks. Khi remote unpublished/left hoặc local leave, dừng render/play theo SDK contract và clear DOM/state reference.
- `client.leave()` đủ để unpublish toàn bộ owned published tracks khi rời call; không explicit unpublish chỉ để cleanup leave.
- Explicit unpublish chỉ dùng khi thay track, camera downgrade hoặc adaptive policy thực sự yêu cầu.
- Owned local tracks phải `stop()` rồi `close()` đúng một lần sau khi không còn được publish/replace operation sở hữu.
- Cleanup reset mute flags, button text/disabled state, selected device state, preview/meter, remote/local containers, network counters, fallback, autoplay CTA và Promise single-flight.
- Cleanup failure của một resource không chặn cleanup các resource còn lại; chỉ log sanitized category/code.
- Cleanup/terminal set latch trước; cleanup cần `leave()` phải queue sau pending transport mutation và được ưu tiên hơn retry. Page disposal có thể ngừng chờ UI bounded, nhưng lease/finalizer vẫn giữ callback reference đủ để late join chạy leave bù trừ; không release singleton cho call generation khác trước compensation.

## 14. Telemetry và security

### 14.1. Được phép

- Timestamp.
- Opaque session ID hoặc hashed/correlatable non-token identifier.
- Event category.
- RTC state.
- Network-quality bucket, không phải raw history theo user.
- Fallback state.
- Device kind, không phải label trước consent.
- Sanitized error code.
- Browser capability flags.

Console diagnostic phải tắt trong production. Backend sanitized aggregate chỉ được dùng nếu codebase đã có sink phù hợp; task này không tạo sink/config mới.

### 14.2. Tuyệt đối cấm

- Agora token/channel token.
- OAuth code/token.
- Cookie, CSRF, credential.
- SDP, ICE candidate, IP address.
- Device label trước consent.
- Raw audio/video.
- Per-user volume history.
- Sensitive exception message/payload/stack chứa các dữ liệu trên.
- SDK log upload.

Mọi HTTP error UI dùng error taxonomy/sanitized code, không render response body tùy ý.

## 15. Automated acceptance matrix

### 15.1. JavaScript test architecture

- Runtime test chuẩn: local verified **Node v24.16.0**, built-in `node:test` + `node:assert`; không `package.json`, npm dependency hoặc bundler.
- Test files: `src/test/js/**/*.test.cjs`.
- Production reliability logic phải tách thành browser-compatible module (không Node-only import) và expose test hooks nhỏ, có namespace rõ ràng; browser vẫn load trực tiếp bằng script/module hiện hữu.
- Test-only runner được phép tại `src/test/js/run-tests.cjs`. Runner dùng Node `fs.globSync('src/test/js/**/*.test.cjs')`, sort path ổn định, fail non-zero nếu zero files; reject source có test `.skip`, `todo` hoặc `only`; rồi `spawnSync(process.execPath, ['--test', '--test-reporter=tap', ...files])`, replay output, parse TAP summary và fail nếu skipped count > 0, đồng thời propagate test exit code. Đây là command cross-platform duy nhất:

  `node src/test/js/run-tests.cjs`

Direct `node --test "src/test/js/**/*.test.cjs"` không được dùng làm gate vì Node v24.16.0 đã xác minh nó exit 0 với zero matching tests trong workspace hiện tại.

| Gate | Loại test | Acceptance |
|---|---|---|
| SDK pin/SRI | Source/markup unit | Chỉ versioned URL 4.24.7; SRI/crossorigin đúng; mutable URL absent |
| Will-expire success | JS unit/mock | Một fetch, một renewToken, state/domain giữ nguyên |
| Will-expire concurrency | JS unit/mock | N event đồng thời dùng đúng một Promise/request/renew |
| Renewal retry/failure | JS fake-timer/mock | Attempts 3, delay 500/1000ms+jitter bound; fatal/non-retry mapping đúng; token không log |
| Did-expire success | JS unit/mock | Serialize leave transport/join same channel+UID, republish usable tracks, không backend leave/join presence mới |
| Did-expire failure | JS unit/mock | FAILED UX, zero domain mutation, explicit retry/end available |
| Ordering/generation | JS unit/mock | Explicit leave/new join làm stale completion no-op và cleanup resource stale |
| Async ownership before deadline | JS deferred Promise + fake clock | Resolve trước 15s success bình thường; reject trước 15s failure bình thường; timer đúng operation được clear |
| Async ownership after deadline | JS deferred Promise + fake clock | Resolve sau 15s giữ UI `FAILED`; reject sau 15s không đổi state/error/retry budget hiện hành; không lifecycle transition |
| Exact deadline boundary ordering | JS deferred Promise + fake monotonic clock/fake scheduler | Đặt clock chính xác `deadlineAt`: resolve và reject đều phải CAS/revoke `TIMED_OUT`, giữ UI timeout/`FAILED` và zero success/current-failure mutation. Chạy cả hai scheduler order `completion -> timer` và `timer -> completion`; kết quả giống nhau, owner không còn `CURRENT`, timer/finalizer idempotent và không lifecycle transition |
| Retry generation isolation | JS deferred Promise + fake clock | Retry trực tiếp, hoặc queued retry sau acquire/revalidation, tạo operation ID/media generation mới đúng một lần; success mới không bị callback cũ ghi đè; callback cũ không clear timer hoặc settle state holder của operation mới |
| Late resource cleanup | JS deferred Promise/mock | Late-created mic/camera/replacement track `stop()+close()` đúng một lần; duplicate callback/cleanup idempotent; stale cleanup không close/unpublish current-generation track |
| Terminal/leave pending operation | JS deferred Promise + fake clock | Backend terminal/`409`, explicit leave và dispose khi Promise pending revoke ownership; late resolve không join/rejoin/publish/recover hoặc tạo session/presence |
| Token/client ownership isolation | JS deferred Promise/mock | Token response/renew completion cũ không apply cho session/client generation mới, không mark client mới renewed, token không log |
| Queued-intent deadline/activation | JS deferred Promise + fake monotonic clock + fake SDK | Giữ lease cũ để intent mới chờ lâu hơn 15s: intent không có operation ID/timer, không consume runtime deadline và không timeout. Sau release, intent valid acquire/revalidate, tạo đúng một operation ID/generation, một `startedAt/deadlineAt` và arm đúng một timer; assert không double timer/second start path, `isCurrentOwner` chạy ngay pre-SDK, SDK đúng một call và timeout 15s của acquired operation vẫn chuyển `TIMED_OUT`/`FAILED` đúng contract |
| Queued-intent invalidation/drop | JS deferred Promise + fake clock + fake SDK | Cancel tombstone, backend terminal/`409`, leave/dispose, session/auth/client/track/cleanup generation change hoặc desired intent không còn latest trong lúc chờ đều làm dequeue revalidation drop intent idempotently: không tạo operation/timer/generation owner và SDK call count bằng `0` |
| Singleton join shared-state isolation | JS deferred Promise + fake clock + fake SDK client | Bao phủ resolve và fake `join` **mutate membership rồi reject** (current trước deadline, timeout/stale, terminal/leave). Mọi settlement inspect actual membership dưới lease; reject current apply failure trước reconcile, stale reject không đổi UI. Assert order `join(old) -> mutate -> reject/resolve -> reconcile leave -> safe marker -> release -> join(new)`, next intent blocked đến safe, final membership chỉ new generation; terminal/leave cho `DISCONNECTED`, drop retry, không rejoin/publish. Reconcile/finalizer lặp vẫn leave đúng một lần |
| Live-track `setDevice` shared-state isolation | JS deferred Promise + fake clock + fake local track | Bao phủ resolve và fake `setDevice(A)` **mutate actual device rồi reject**. Switch B bị blocked đến inspect/reconcile hoàn tất; assert failure ordering, final actual device/selection là latest authorized B hoặc old track bị quarantined/retired và verified replacement sở hữu B. Terminal/leave/track replacement drop B theo contract; reconciliation failure không release unsafe track; duplicate reconcile close old-owned track đúng một lần và không mutate/close current-generation replacement |
| Same-client `renewToken` shared-state isolation | JS deferred Promise + fake clock + fake SDK client | Bao phủ resolve và fake `renewToken(T1)` **mutate token rồi reject**. Reject/current failure và stale/timeout đều khiến exact client `TOKEN_STATE_UNKNOWN`, block next intent, serialized retire/leave trước release; replacement generation fetch T2 mới và final current client chỉ mang T2. Assert order reconcile/retire-before-release/next join, token cũ không xuất hiện trên client/session mới; terminal/leave drop next intent; duplicate settlement/retire idempotent |
| Shared-mutation rejected-settlement safety | JS deferred Promise + fake clock + fake SDK objects | Parameterized `join`/`setDevice`/`renewToken` mutate actual shared object rồi reject ở trước deadline, sau timeout và sau terminal/leave. Assert cả resolve/reject đều gọi `postSettlementReconcile` dưới cùng exact-object lease; timeout/stale reject chỉ sanitized diagnostic, current reject terminalize/apply failure trước reconcile; reconcile failure quarantine/retire và giữ next intent blocked cho đến verified replacement/safe state; không ownership transfer hoặc lifecycle mutation sớm |
| Shared staged-success publication gate | JS deferred Promise + fake SDK objects | Parameterized `join`/`setDevice`/`renewToken`: sau resolve và logical CAS success nhưng trước khi deferred reconcile trả về, assert staged result tồn tại nhưng UI/current resource/renewed marker chưa publish, exact object chưa transfer và lease chưa release. Chỉ `SAFE_VERIFIED` đã atomically record trước, cộng authorization/session/object/generation/latest-desired-state recheck còn đúng, mới cho một atomic publish/transfer; recheck fail thì discard stage |
| Quarantine terminal-marker isolation | JS deferred Promise + injected reconcile failure | Reconcile failure phải retire/close/leave exact old object idempotently rồi ghi duy nhất `QUARANTINED_RETIRED`; assert không bao giờ có `SAFE_VERIFIED`/`markReconciled`, staged result không publish, old object non-callable/non-transferable, next generation bind replacement object identity đã verify và không gọi API trên old object |
| Shared marker/release ordering and duplicates | JS deferred Promise + ordered spy | Với cả safe và quarantine path, assert terminal marker tương ứng được atomically record trước lease release; không release khi chưa có marker. Duplicate settlement/finalizer/reconcile chỉ đọc cùng terminal marker, không publish/transfer/retire/close/leave/release hai lần và không tạo cả hai marker cho cùng settlement key |
| Token endpoint auth | Spring controller/security integration, mocked service; không dev DB | Existing `GET /api/sessions/{sessionId}/token`: participant success trả token DTO; unauthenticated entry-point/401, non-participant 403, missing 404, terminal 409; không rename/redirect/new route |
| Join endpoint auth | Spring controller/security integration, mocked service; không dev DB | Existing `POST /api/sessions/{sessionId}/join-agora`: participant success; unauthenticated/CSRF failure, non-participant 403, missing 404, terminal 409 |
| Terminal 409 frontend | JS unit/mock | Stop media recovery, cleanup, terminal UI, no session/presence creation |
| Listener/concurrency lifecycle | JS unit/mock | Register exactly once; concurrent duplicate events/leave/rejoin coalesce; teardown uses same references; stale completion no-op |
| Pre-call taxonomy | JS unit/mock | Unsupported/denied/missing/busy/IO mapping; mic blocks; camera allows audio-only; cancel uses existing lifecycle command only |
| Device changes | JS unit/mock | Preserve selected device, deterministic fallback, safe replacement ownership, mic blocking/camera audio-only/playback graceful |
| Autoplay | JS unit/mock | Event và play rejection show CTA; resume/play only under simulated gesture; no false success before running/play resolution |
| Network mapping | JS fake-timer/mock | 3 bad degrade, 5 good recover, unknown/3 reset rules, 10s anti-oscillation, reconnect mapping |
| Connection mapping | JS fake-timer/mock | Mọi row 6.3.1/6.3.2, explicit leave/token/transient tách biệt, 15s deadline deterministic, backend terminal thắng và stale events không resurrect |
| Exception/error mapping | JS unit/mock | Exact numeric exception pairs và exact `AgoraRTCErrorCode` categories theo 6.5; unknown safe default; raw msg/data/stack không log; zero domain mutation |
| Dual/fallback | JS unit/mock | Exact numeric enum/signatures; enable after video publish; events drive UI; unsupported disables adaptation only |
| Volume | JS fake-timer/mock | Enable after join/rejoin; <=4Hz render; reset on leave; no persistence/log samples |
| Agora/domain boundary | JS unit/mock + backend service spy | Mọi requested Agora event/failure tạo zero call tới leave/end/finalize/session/presence APIs |
| Cleanup | JS unit/mock | Idempotent leave; stop+close owned locals/previews; mediaType-aware unpublish/user-left; cleanup tiếp tục khi một stop/close/leave fail; no remote close requirement; UI reset |
| Persistence after reload/recovery | Existing Spring service/integration tests with isolated test DB | Exactly one `LearningSession`; at most one open `SessionPresence` per participant; accepted WS disconnect/recovery semantics unchanged |
| Lifecycle V2 regression | Existing unit/integration suite | Join/leave/recovery/timeout/overlap semantics giữ nguyên |
| Security regression | Existing security suite | Auth, CSRF, participant authorization giữ nguyên |
| Admin regression | Existing suite only | Không sửa Admin; suite hiện hữu PASS |

Automated tests không dùng development MySQL/DB. Controller/security integration dùng mock/in-memory test arrangement hiện hữu; không truy cập dữ liệu dev. Không được đánh dấu gate PASS nếu test bị skip.

Các gate race/ownership trên bắt buộc dùng controllable/deferred Promise và fake monotonic clock; cấm phụ thuộc wall-clock hoặc timing scheduler thật. Test phải assert cả positive path (resolve/reject trước deadline) và negative side effects: UI/state holder, timer ID, retry budget, current resource identity, lifecycle spy và sanitized diagnostics. Với shared SDK APIs, chỉ assert UI/state holder là chưa đủ: fake client/track phải mutate state ngay trong deferred SDK operation trước Promise settle trong **cả kịch bản resolve và mutate-then-reject**, ghi call order/max concurrent calls/actual membership/device/token. Test phải chứng minh max concurrency bằng `1`, current-owner reject terminalize trước reconciliation, shared resolve chỉ stage và không publish/transfer trước `SAFE_VERIFIED`, mọi resolve/reject reconciliation terminalize bằng đúng một marker trước lease release/ownership transfer/next intent, next intent bị blocked khi object chưa safe, và final fake SDK resource không mang mutation của stale generation. Inject reconciliation failure để chứng minh object được retire rồi ghi `QUARANTINED_RETIRED`, không bao giờ ghi safe marker hoặc transfer unsafe object; generation kế tiếp phải dùng replacement identity. Terminal/leave cases và duplicate settle/cleanup/reconcile/finalizer/marker/release phải idempotent.

## 16. Manual two-browser matrix — đúng 12 case

Race ownership không được nghiệm thu bằng cách cố tạo callback race thủ công thiếu ổn định; evidence authoritative cho race là automated tests deterministic section 15. Trong các case manual liên quan timeout/recovery, gate quan sát chỉ yêu cầu: operation timeout hiển thị failure, user retry tạo attempt mới, UI không tự hồi sinh khỏi `FAILED` do completion cũ, không duplicate local/remote media và không tạo duplicate `LearningSession`/`SessionPresence`.

### 16.1. Prerequisites và evidence

- Hai account participant A/B và account non-participant C.
- Hai browser profile độc lập; ít nhất Chrome/Edge desktop mới nhất trong môi trường nghiệm thu, HTTPS/localhost secure context.
- Camera, microphone, speaker thật. Case 6 và 7 mỗi case bắt buộc có hai thiết bị cùng kind: hai thiết bị vật lý, hoặc thiết bị virtual được ghi rõ tên/version/configuration trong evidence. Nếu thiếu prerequisite này, case là `BLOCKED — DEVICE PREREQUISITE`, không phải PASS; automated mock không thay manual evidence.
- Có khả năng mô phỏng offline/throttling và token ngắn hạn trong test-only environment mà không sửa production config.
- DevTools network/console phải redact token/credential; không chụp token response body.
- Evidence mỗi case: timestamp, browser/version, device kind, bước thực hiện, ảnh/video UI đã redact, network/API status không chứa secret, kết quả thực tế.
- Evidence chỉ được reuse khi cùng build SHA, browser major, OS, device topology và setup. Khác bất kỳ điều kiện nào phải chạy lại.
- Mỗi case bắt đầu `NOT RUN`; chỉ người thực thi manual đổi thành `PASS`, `FAIL` hoặc `BLOCKED`. Không infer PASS từ automated test hoặc case khác.

| # | Case identity bắt buộc | Independent evidence checklist | Status |
|---:|---|---|---|
| 1 | Login + matchmaking | A/B login độc lập; selection; MATCHED cùng opaque session; timestamp/build/browser evidence | NOT RUN |
| 2 | Local/remote AV | A/B thấy/nghe hai chiều; mic mandatory; camera/audio-only behavior; sanitized screenshot/video | NOT RUN |
| 3 | Token renewal current session | (a) will-expire fetch+renew liên tục; (b) did-expire transport rejoin same session/channel/UID + republish exactly once; domain snapshot không đổi | NOT RUN |
| 4 | Network OFF temporary/recover within grace | Tắt mạng tạm thời rồi bật trong grace; precedence đúng; same backend session; không Agora-derived leave/finalize | NOT RUN |
| 5 | Weak network/adaptive fallback safe | Throttle đủ threshold; high->low/audio-only->recover; anti-oscillation; speaking safe; domain snapshot giữ nguyên | NOT RUN |
| 6 | Camera switch **và** unplug/replug — mọi subcase bắt buộc | Evidence riêng cho từng bước: (a) chọn camera A rồi chuyển A -> B bằng UI và remote xác nhận video B; (b) rút camera đang sử dụng; (c) UI phát hiện đúng camera mất; (d) chọn hoặc deterministic fallback sang camera còn lại, không hai video track cùng publish; (e) cắm lại camera; (f) thiết bị xuất hiện lại và có thể chọn bằng UI; (g) toàn bộ chuỗi không reload; (h) backend evidence vẫn đúng một `LearningSession` và không thêm/mở duplicate `SessionPresence`. Thiếu bất kỳ evidence nào không PASS. | NOT RUN |
| 7 | Microphone switch **và** unplug/replug — mọi subcase bắt buộc | Evidence riêng cho từng bước: (a) chọn microphone A rồi chuyển A -> B bằng UI; (b) rút microphone đang sử dụng; (c) UI phát hiện đúng microphone mất và hiện blocking degraded trong gap; (d) chọn hoặc deterministic fallback sang microphone còn lại, không hai audio track cùng publish; (e) cắm lại microphone; (f) thiết bị xuất hiện lại và có thể chọn bằng UI; (g) browser B/remote audio recording hoặc meter đã redact xác nhận đúng mic sau switch/fallback/replug; (h) toàn bộ chuỗi không reload; (i) backend evidence vẫn đúng một `LearningSession` và không thêm/mở duplicate `SessionPresence`. Thiếu bất kỳ evidence nào không PASS. | NOT RUN |
| 8 | Autoplay blocked | Reproduce audio block; critical gesture CTA + bounded observe; video-only non-blocking; không infer PASS nếu không reproduce | NOT RUN |
| 9 | Reload within grace same session | Reload/recovery dùng đúng same session, không duplicate listener/join/presence | NOT RUN |
| 10 | Terminal cannot token/join, both endpoints 409 | Sau terminal, `GET /api/sessions/{id}/token` và `POST /api/sessions/{id}/join-agora` đều 409; media recovery dừng | NOT RUN |
| 11 | No duplicate LearningSession/open SessionPresence | DB evidence từ isolated test environment: exactly one LearningSession; mỗi participant at most one open SessionPresence sau reload/recovery | NOT RUN |
| 12 | Evidence reuse validation | Reuse chỉ khi exact build SHA/browser major/OS/device topology trùng; mismatch phải reject và chạy lại; lưu quyết định reuse/reject | NOT RUN |

## 17. `P0_GATE`: packaging, regression, DB và immutable checks

`P0_GATE` là tên duy nhất cho toàn bộ sequence dưới đây. Adaptive policy (section 11), volume (section 12), packaging approval và Definition of Done đều phải tham chiếu nguyên gate này; không dùng một subset để tuyên bố P0 PASS.

### 17.1. Sequence bắt buộc

Chạy từ repository root, fail-fast, lưu exit code/output đã redact:

1. SDK/source check: versioned 4.24.7 URL/SRI đúng, mutable `/sdk/release/AgoraRTC_N.js` absent, production module browser-compatible.
2. Targeted Node P0 và toàn bộ P0 Node suite: `node src/test/js/run-tests.cjs`. Runner fail nếu zero test hoặc có skip/todo/only.
3. Targeted Spring token/security/controller:

   `./gradlew clean test --tests "*LearningSessionControllerTests" --tests "*SecurityIntegrationTests" --tests "*AgoraTokenServiceTests"`

4. Targeted Lifecycle V2:

   `./gradlew clean test --tests "*LearningSessionServiceImplTests" --tests "*BoundaryConditionTests" --tests "*SessionFinalizerTests" --tests "*SessionSchedulerTests" --tests "*MatchMakingServiceTests" --tests "*MatchMakingControllerTests"`

5. Sau PR #9/Admin integration, targeted Admin regression bằng exact known classes:

   `./gradlew clean test --tests "*AdminRubricStaticTests" --tests "*DataInitializerRubricTests" --tests "*AdminControllerTests" --tests "*AdminServiceAuthorizationTests" --tests "*AdminRubricServiceImplTests" --tests "*AdminUserServiceImplTests"`

   Trước integration, các class này absent nên không chạy command targeted này; full suite ở bước 6 vẫn là regression authority của branch hiện tại. Không dùng wildcard zero-test như PASS.

6. Full test: `./gradlew clean test`.
7. Full package: `./gradlew clean build`.
8. Whitespace: `git diff --check`.
9. Secret scan không in value, chỉ tên file:

   `rg -l -i '(agora[^\r\n]{0,24}(token|certificate|secret)|oauth[^\r\n]{0,24}(token|secret|code)|csrf[^\r\n]{0,24}token|cookie[^\r\n]{0,16}=|authorization[^\r\n]{0,16}bearer|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|password\s*[:=]\s*[^$<{\s])' . -g '!build/**' -g '!.git/**'`

   Reviewer mở từng filename match bằng sanitized view, không copy/in giá trị. Expected false positives được review thủ công: environment placeholders trong `application.properties`, dummy CI values, test method/string mô tả forbidden token/secret và chính regex trong spec/test harness. Bất kỳ literal credential/token ngoài approved dummy fixture làm gate FAIL.
10. Migration immutable check theo section 17.2.
11. Disposable isolated MySQL 8 clean/upgrade/Hibernate validation theo section 17.3.

Các bước 1-11 là full `P0_GATE`. Manual 12-case matrix không nằm trong `P0_GATE` để tránh vòng lặp với P1 adaptive; matrix được chạy sau implementation P1 và mọi required row phải có independent evidence/PASS trước nghiệm thu cuối.

### 17.2. Flyway V1-V4 và security invariants

- Baseline branch hiện tại có V1-V3 và **không có V4**. Git blob hashes authoritative tại `origin/feature/learning-session-v2` commit `52b2c82330d1589639da040cfebfaf662320eb42`:

  - V1 `7ca6367b72ad5469de797a64ba657d1d69ac9ae9`
  - V2 `386f4758aa64d45b35ee3f906fb27cfae3988879`
  - V3 `d7c3ba407dc6e36973ffd698d83befd499a60bf3`

- Gate hiện tại: `git diff --exit-code origin/feature/learning-session-v2 -- src/main/resources/db/migration/V1__initial_schema.sql src/main/resources/db/migration/V2__lifecycle_v2_schema.sql src/main/resources/db/migration/V3__lifecycle_v2_data_migration.sql`; recompute `git hash-object` từng file và compare ba hash trên; `Test-Path src/main/resources/db/migration/V4__admin_rubric_schema.sql` phải là false.
- Sau PR #9/Admin integration, V4 được phép hiện diện nhưng phải byte-identical với authoritative merged Admin base: lấy merged-base commit đã integrate PR #9, chạy `git rev-parse <merged-base>:src/main/resources/db/migration/V4__admin_rubric_schema.sql` và compare với `git hash-object` working file; V1-V3 vẫn compare hashes trên. Không sửa V4 trong feature này.
- V1-V4, khi hiện diện theo base tương ứng, immutable về tên/content/checksum; task này không tạo migration mới.
- Không dùng `ddl-auto` để thay schema và không truy cập DB dev trong test/audit.
- Không đổi participant authorization, authenticated principal ownership, CSRF cho mutation hoặc terminal `409` semantics.
- Client không quyết định identity, channel hoặc UID.
- Không thêm secret/config mặc định, không log token và không đưa secret vào test fixture/evidence.
- Không giảm security headers/CORS/WebSocket origin rules.

### 17.3. Disposable isolated MySQL 8 gates

Docker `29.6.2` đã được xác minh local. Implementation được phép thêm test-only tracked script `src/test/scripts/run-isolated-mysql-gates.ps1`, chạy bằng:

`pwsh -NoProfile -File src/test/scripts/run-isolated-mysql-gates.ps1`

Script contract bắt buộc:

1. Tạo unique Docker network/container/database names bằng GUID; generate root/app password ngẫu nhiên trong memory, không echo và không ghi file; image pin `mysql:8.0.46`.
2. `try/finally` luôn `docker rm -f` exact validated container và `docker network rm` exact network; không dùng dev host/database/credential và không expose fixed host port.
3. Chờ `mysqladmin ping` trong container với bounded 120-second timeout; logs được redact, không in command chứa password.
4. **Clean install gate:** database rỗng, chạy application test context/Flyway V1-V3 từ current branch bằng environment overrides chỉ trong process; assert Flyway success và schema history đúng versions/checksums.
5. **Supported legacy/upgrade gate:** database mới thứ hai trong cùng container; seed supported legacy fixture `src/test/resources/fixtures/lifecycle_v2_legacy.sql` bằng stdin kín, chạy V1-V3 upgrade path, assert migrated lifecycle rows/presence invariants và rerun idempotency theo existing lifecycle spec.
6. **Hibernate validation gate:** start Spring test/application context bằng test profile với `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`, Flyway enabled và datasource trỏ disposable DB; process phải start successfully rồi shutdown bounded. Không sửa production config.
7. Sau PR #9/Admin integration, clean/upgrade paths chạy đến V4 và validate authoritative V4 checksum; trước integration V4 phải absent.
8. Mọi sub-gate fail/timeout trả non-zero. Script không được skip gate khi Docker available; cleanup chạy cả khi test fail.

## 18. Definition of Done

- Spec đã qua independent Review và Lead chỉ đạo `APPROVED` trước khi code.
- Agora 4.24.7 versioned URL và SRI đúng byte artifact đã chốt.
- Toàn bộ `P0_GATE` PASS; Agora events không thay đổi Lifecycle V2 domain state.
- P1 adaptive/volume chỉ được triển khai sau full `P0_GATE` PASS và có automated evidence riêng.
- Full clean test/build, isolated MySQL gates và immutable/security checks PASS; không production migration/config/dependency change.
- Manual matrix có evidence thật; không có PASS suy diễn.
- Không có token/credential/media/device-label-before-consent trong log, UI, storage hoặc telemetry.
- Không còn quyết định thiết kế mở trong phạm vi spec này.

## 19. Nguồn xác minh version/API

- Agora official versioned artifact: `https://download.agora.io/sdk/release/AgoraRTC_N-4.24.7.js`.
- Agora-maintained npm package metadata: `https://registry.npmjs.org/agora-rtc-sdk-ng/4.24.7`.
- Versioned 4.24.7 English type declaration: `https://cdn.jsdelivr.net/npm/agora-rtc-sdk-ng@4.24.7/rtc-sdk_en.d.ts`.
- Các signature đã chốt từ declaration 4.24.7: token events + `renewToken`; `connection-state-change`; `media-reconnect-start/end`; device/autoplay/audio-context module events; `checkSystemRequirements`; device enumeration; `enableDualStream`; `setRemoteVideoStreamType(uid, 0|1)`; `setStreamFallbackOption(uid, 2)`; stream/fallback events; volume indicator.
