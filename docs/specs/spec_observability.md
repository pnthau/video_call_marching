# SPEC — OBSERVABILITY

## 1. Trạng thái

- Trạng thái: **APPROVED — baseline audit đã hoàn tất; chỉ cho phép implementation planning sau independent review**.
- Phạm vi: observability cho ứng dụng Spring Boot hiện tại, gồm health/readiness, HTTP metrics, business metrics, correlation ID, sanitized logging và operational runbook.
- Spec này không cấp quyền sửa production code, migration, stage, commit hoặc push.
- Nhánh dự kiến: `feature/observability`.
- Dependency base: **đã chốt** bằng pre-flight từ graph Git thực tế; xem mục 22.1.
- Không base trực tiếp từ `feature/agora-reliability` chỉ để lấy frontend Agora khi nhánh đó vẫn có Manual Two-Browser Gate ở trạng thái `BLOCKED — DEVICE PREREQUISITE`.
- Peer Rating và Admin/Rubric tạm thời ngoài phạm vi; Observability không được tự kéo các feature đó vào.

## 2. Bối cảnh và mục tiêu

Observability phải giúp người vận hành trả lời được, bằng tín hiệu an toàn và có thể kiểm chứng:

1. Ứng dụng còn sống và đã sẵn sàng nhận traffic hay chưa?
2. Database và migration state có cho phép phục vụ request an toàn hay không?
3. Endpoint nào đang lỗi, bị rate-limit hoặc có độ trễ bất thường?
4. Matchmaking, learning-session lifecycle và Agora backend integration đang thành công hay thất bại ở bước nào?
5. Một lỗi có thể được truy vết qua các boundary mà không ghi lộ token, credential, PII hoặc domain identifier nhạy cảm hay không?
6. Metrics/logging bị lỗi có làm thay đổi business outcome hay không?

Mục tiêu không phải xây dựng đầy đủ monitoring platform. Spec này tạo instrumentation contract ổn định để Prometheus, Grafana hoặc nền tảng khác có thể tích hợp sau.

## 3. Ngoài phạm vi

- Không triển khai Prometheus server, Grafana, Loki, ELK/OpenSearch hoặc SaaS monitoring trong phase này.
- Không triển khai distributed tracing backend hoặc OpenTelemetry Collector nếu chưa có quyết định riêng.
- Không thêm Redis, message broker, observability database hoặc telemetry persistence vào application database.
- Không sửa domain lifecycle, matchmaking semantics, authorization hoặc Agora media behavior.
- Không tạo Peer Rating hoặc Admin/Rubric metrics cho feature chưa tồn tại trên dependency base.
- Không thu thập nội dung audio/video, request body, multipart body hoặc nội dung chat.
- Không đưa production credential, dashboard export hoặc deployment secret vào repository.
- Không tuyên bố production readiness chỉ dựa trên automated observability tests.

## 4. Nguyên tắc bắt buộc

### 4.1 Privacy by default

Không được ghi vào log, metric name, label/tag, health detail, response header hoặc error payload:

- Password, database password hoặc connection secret.
- Agora App Certificate, channel token hoặc access token.
- OAuth authorization code/access token/refresh token.
- Cookie, session cookie, CSRF value hoặc Authorization header.
- Request/response body.
- Multipart filename hoặc file contents.
- Email, username, principal name, user ID hoặc profile ID.
- Learning-session ID, channel name, match ID hoặc raw path variable.
- Raw query string.
- Raw SDK/exception message chưa được sanitize.
- IP address hoặc user-agent nếu chưa có quyết định privacy riêng.

### 4.2 Bounded cardinality

- Metric labels chỉ được dùng tập giá trị hữu hạn, đã liệt kê trong spec hoặc resolver contract.
- Không dùng raw URI. Chỉ dùng route template đã chuẩn hóa hoặc nhóm endpoint tĩnh.
- Không dùng exception message làm label.
- Không dùng correlation ID làm metric label.
- Không dùng timestamp, filename, principal hoặc bất kỳ domain identifier nào làm label.
- Unknown value phải gom về giá trị tĩnh như `unknown` hoặc `other`; không phản chiếu input.

### 4.3 Failure isolation

- Lỗi ghi metric hoặc bổ sung logging context không được làm request business đang hợp lệ thất bại.
- Instrumentation không được gọi lại controller/service/repository.
- Instrumentation không được tạo domain mutation, session hoặc presence.
- Không được nuốt business exception hoặc thay đổi mapping HTTP hiện có.
- Không được biến `400/401/403/404/405/409/413/429/500` thành status khác.
- Nếu một custom health contributor lỗi, kết quả phải deterministic và sanitized; không ném raw exception ra response.

### 4.4 Authority

- Backend lifecycle hiện tại tiếp tục authoritative.
- Metrics mô tả outcome đã xảy ra; metrics không quyết định domain outcome.
- Log/metric success chỉ được ghi sau boundary được định nghĩa là thành công.
- Retry hoặc duplicate callback không được đếm thành nhiều business success nếu domain operation chỉ thành công một lần.

## 5. Pre-flight và dependency gate

Trước khi chốt spec `APPROVED`, thực hiện read-only audit:

1. Đọc đầy đủ `AGENTS.md` và các spec đã được merge trên candidate base.
2. Chạy:
   - `git status --short --branch`
   - `git worktree list`
   - `git branch -vv`
   - `git log --oneline --decorate -15`
   - `git diff --check`
3. Xác định chính xác candidate dependency base và các feature đã hiện diện.
4. Xác định Spring Boot, Spring Security, Micrometer và Actuator version từ build metadata thực tế.
5. Xác định Actuator/Micrometer đã có dependency hay chưa; không đoán version.
6. Xác định security filter chain, exception boundary, rate-limiter filter/interceptor và request mappings thực tế.
7. Xác định logging framework, pattern/configuration, MDC usage và async execution hiện có.
8. Xác định database/Flyway health behavior hiện tại.
9. Phân loại toàn bộ changed/untracked files; không dùng dirty worktree không rõ nguồn gốc làm base.
10. Xác minh migration hiện có và ghi baseline hash; migration là immutable.

Nếu candidate base không chứa Security Hardening đã được delivery nhưng Observability cần metric cho rate limiting/exception boundary, phải chốt lại dependency base trước implementation. Không cherry-pick tùy ý trong implementation task.

## 6. Dependency policy

- Ưu tiên Spring Boot Actuator và Micrometer version do Spring Boot dependency management quản lý.
- Không ghi version đoán tay nếu BOM/plugin hiện tại đã authoritative.
- Nếu phải thêm dependency mới ngoài Actuator/Micrometer core, Implement phải báo Lead trước khi sửa build file.
- Mọi dependency mới phải được Review kiểm tra compatibility, transitive dependency, license và security implications.
- Không thêm vendor registry/exporter cho đến khi export target được quyết định riêng.

## 7. Actuator exposure và security contract

### 7.1 Web exposure allowlist

Allowlist mặc định:

- `health`
- `info` chỉ khi content hoàn toàn tĩnh và không chứa build path, Git remote, username hoặc environment details.
- `prometheus` chỉ khi Prometheus registry được phê duyệt và endpoint được bảo vệ theo deployment model.

Không expose qua web:

- `env`
- `configprops`
- `beans`
- `mappings`
- `heapdump`
- `threaddump`
- `loggers`
- `conditions`
- `scheduledtasks`
- `caches`

Endpoint không có trong allowlist phải không truy cập được từ public application surface.

### 7.2 Liveness

- Liveness chỉ phản ánh process/application runtime có thể tiếp tục hoạt động.
- Liveness không phụ thuộc database hoặc external provider để tránh restart loop khi dependency tạm thời lỗi.
- Response công khai không chứa component exception hoặc internal detail.

### 7.3 Readiness

- Readiness phản ánh ứng dụng có thể phục vụ request phụ thuộc database an toàn hay không.
- Database connectivity và migration compatibility phải được đánh giá theo behavior thực tế của Spring Boot/Flyway.
- External service không bắt buộc cho mọi request không được mặc định làm toàn bộ application `OUT_OF_SERVICE`.
- Nếu thêm contributor cho Agora configuration, chỉ báo trạng thái cấu hình ở dạng boolean/state hữu hạn; không thực hiện token generation và không trả app ID/certificate.

### 7.4 Response detail

- Anonymous/public health response chỉ cho phép status tổng quát.
- Component detail chỉ dành cho principal/role quản trị được xác định trong security model thực tế hoặc management network nội bộ.
- Health response không chứa JDBC URL, hostname nội bộ, stack trace, exception message hoặc credential.
- Authentication/authorization hiện tại phải được giữ nguyên; Actuator không được mở backdoor mới.

## 8. Correlation ID contract

### 8.1 Header

- Response header chuẩn: `X-Correlation-ID`.
- Request header cùng tên chỉ được chấp nhận khi giá trị hợp lệ và trust policy cho phép.
- Nếu chưa có trusted proxy/request-ID policy, server tự tạo ID mới thay vì tin client.

### 8.2 Validation

- Giá trị là opaque identifier, tối đa 64 ký tự ASCII.
- Chỉ cho phép `[A-Za-z0-9._-]`.
- Giá trị rỗng, quá dài hoặc chứa ký tự ngoài allowlist phải bị bỏ qua và thay bằng ID do server tạo.
- Không phản chiếu giá trị header không hợp lệ.
- Correlation ID không phải authentication data, authorization data hoặc idempotency key.

### 8.3 Lifecycle

- Thiết lập correlation ID trước application logging cần thiết.
- Đưa vào MDC với một key cố định được chốt khi implementation.
- Response nhận đúng ID hiệu lực.
- MDC phải được cleanup trong `finally`, kể cả exception và async dispatch.
- Với async boundary, context chỉ được propagate bằng cơ chế explicit đã test; không dựa vào thread-local tự lan truyền.
- Không thêm correlation ID vào metric tags.

## 9. HTTP metrics contract

Ưu tiên metric chuẩn của Spring Boot/Micrometer nếu đã đáp ứng contract. Không tạo custom metric trùng nghĩa.

Tín hiệu bắt buộc:

- Request count.
- Request duration.
- In-flight request nếu framework hỗ trợ an toàn.
- Method.
- Normalized route/template hoặc static scope.
- HTTP status/outcome hữu hạn.

Quy tắc:

- Không tag raw URI hoặc query string.
- Unmatched route gom về `NOT_FOUND`/`unknown`, không dùng path thực tế.
- Không gắn principal, session ID hoặc correlation ID.
- Status phải phản ánh final HTTP response sau exception mapping.
- Metrics không được khiến response body bị buffer/read ngoài nhu cầu hiện có.
- Không tự instrument actuator scrape endpoint theo cách tạo feedback loop không giới hạn.

## 10. Business metrics catalog

Tên metric cuối cùng phải theo convention Micrometer thực tế sau baseline audit. Semantic contract dưới đây là authoritative.

| Signal | Type | Labels được phép | Thời điểm ghi |
| --- | --- | --- | --- |
| Matchmaking outcome | Counter | `outcome=matched|cancelled|timeout|rejected|error` | Khi operation đạt outcome authoritative |
| Waiting queue size | Gauge | `level` chỉ khi level là enum hữu hạn hiện có | Đọc từ state authoritative, không tạo queue mới |
| Session lifecycle transition | Counter | `from`, `to` là enum lifecycle hữu hạn | Sau transition thành công |
| Session join outcome | Counter | `outcome=success|unauthorized|forbidden|not_found|terminal|conflict|error` | Sau final outcome |
| Session leave outcome | Counter | Cùng tập outcome hữu hạn phù hợp | Sau final outcome |
| Agora token outcome | Counter | `operation=issue|renew`, `outcome=success|unauthorized|terminal|error` | Không log/label token |
| Rate-limit decision | Counter | `policy`, `decision=allowed|rejected|error` | Chính xác một lần mỗi decision |
| Avatar upload outcome | Counter | `outcome=success|empty|type_rejected|size_rejected|decode_rejected|storage_error|error` | Sau final outcome |
| Application exception | Counter | `boundary=api|mvc`, `category`, `status` | Sau sanitized mapping |

### 10.1 Policy label

`policy` chỉ được dùng enum rate-limit đã định nghĩa trong Security Hardening, ví dụ:

- `SESSION_TOKEN`
- `SESSION_ACTIVE`
- `SESSION_JOIN`
- `SESSION_LEAVE`
- `AVATAR_UPLOAD`
- `ADMIN_MUTATION`

Không tạo policy label từ URI, controller name hoặc input của client.

### 10.2 Exception category

Category phải là tập hữu hạn do server xác định, ví dụ:

- `validation`
- `authentication`
- `authorization`
- `not_found`
- `method_not_allowed`
- `conflict`
- `payload_too_large`
- `rate_limited`
- `internal`

Không dùng Java exception class hoặc exception message làm public metric label nếu nó tạo surface không ổn định. Nếu class được dùng nội bộ để debug thì chỉ log class name đã sanitize, không dùng message.

### 10.3 Duplicate and retry semantics

- HTTP attempts có thể được đếm ở HTTP metric.
- Business-success metric chỉ đếm authoritative success.
- Duplicate callback, stale async completion hoặc idempotent cleanup không tạo business success mới.
- Backend terminal outcome thắng callback/recovery như lifecycle contract hiện tại.

## 11. Logging contract

### 11.1 Event shape

Mỗi operational log do phase này thêm chỉ được chứa các field hữu hạn cần thiết:

- `event`
- `outcome`
- `correlationId`
- `policy` khi phù hợp
- `status` hoặc `category` đã chuẩn hóa
- `durationBucket` chỉ khi có quyết định cụ thể; ưu tiên metrics cho duration
- `exceptionClass` chỉ cho lỗi nội bộ và không kèm message

Không bắt buộc chuyển toàn hệ thống sang JSON logging trong cùng phase nếu baseline hiện tại không hỗ trợ. Dù dùng text hay JSON, field semantics và privacy contract vẫn giữ nguyên.

### 11.2 Log levels

- `INFO`: lifecycle/operation quan trọng đã hoàn tất, với sampling/volume hợp lý.
- `WARN`: degraded/rejected/recoverable state cần chú ý, không dùng cho hành vi người dùng bình thường nếu gây noise.
- `ERROR`: unexpected internal failure cần điều tra.
- Không log cùng exception ở nhiều layer nếu không bổ sung thông tin mới.
- Không log stack trace cho expected `4xx`.

### 11.3 Sanitization

- Không ghi raw exception message từ SDK, database, multipart parser hoặc security provider.
- Không serialize request object, authentication object hoặc headers.
- Không nối raw URI vào message.
- Sanitization phải được test tại output boundary, không chỉ test helper.
- Unknown error dùng message/event tĩnh.

### 11.4 Retention

- Source repository chỉ chứa logging configuration/template, không chứa runtime log.
- Không commit startup log, test transcript hoặc exported telemetry.
- Production retention, rotation và access-control phải được ghi trong deployment runbook; giá trị retention cuối cùng cần deployment owner phê duyệt.

## 12. Alert và SLI contract

Phase này phải tạo runbook với signal, ý nghĩa, cách xác minh và hành động; không tuyên bố ngưỡng production tối ưu khi chưa có baseline traffic.

Candidate signals:

1. Readiness không `UP` trong khoảng thời gian liên tục.
2. Tỷ lệ HTTP `5xx` tăng.
3. p95/p99 latency tăng trên endpoint group quan trọng.
4. `429` tăng theo policy.
5. Matchmaking timeout/error tăng.
6. Session join conflict/terminal/error tăng bất thường.
7. Agora token issue/renew error tăng.
8. Database health xuống.
9. Waiting queue tăng nhưng matched outcome không tăng tương ứng.

Mỗi alert proposal phải ghi:

- Metric/query dự kiến.
- Window.
- Severity.
- Runbook action.
- False-positive risk.
- Trạng thái `PROVISIONAL` cho đến khi có dữ liệu baseline.

## 13. Configuration contract

- Development/test/production config phải tách rõ.
- Secret chỉ lấy từ cơ chế configuration an toàn hiện có; không hardcode.
- Public actuator exposure phải tối thiểu.
- Health detail và management port/bind address phải được quyết định theo deployment topology thực tế.
- Không bật endpoint nguy hiểm để thuận tiện debug.
- Không bật exemplar/tracing baggage có chứa PII.
- Restart application được phép reset in-memory metric state; spec không yêu cầu metric persistence trong process.

## 14. Implementation slices

### Slice 1 — Health and Actuator security

- Dependency/configuration cần thiết.
- Liveness/readiness groups.
- Database/Flyway behavior.
- Exposure allowlist.
- Security rules và sanitized health response.
- Targeted tests và independent review.

### Slice 2 — Correlation ID and HTTP metrics

- Correlation-ID generation/validation/response/MDC cleanup.
- Async/error dispatch behavior.
- HTTP request count/duration/status/template metrics.
- Cardinality tests.
- Targeted tests và independent review.

### Slice 3 — Business metrics

- Matchmaking.
- Learning-session lifecycle và join/leave.
- Agora token issue/renew backend outcomes nếu code tồn tại trên base.
- Rate-limit và avatar outcome nếu code tồn tại trên base.
- Exact-once-at-boundary semantics.
- Targeted tests và independent review.

### Slice 4 — Sanitized logging and runbook

- Operational event catalog.
- Log-level/noise policy.
- Output-boundary privacy tests.
- Provisional SLI/alert runbook.
- Full automated gate và independent final review.

Không chuyển slice nếu còn CRITICAL/HIGH/MEDIUM finding.

## 15. Tests tối thiểu

### 15.1 Health and security

1. Liveness `UP` khi application runtime bình thường.
2. Database failure không làm liveness fail.
3. Database failure làm readiness thay đổi đúng contract.
4. Anonymous health response không lộ component detail.
5. Authorized/internal access có đúng detail được phép.
6. Endpoint ngoài allowlist không truy cập được.
7. `/env`, `/configprops`, `/heapdump`, `/threaddump` không public.
8. Health contributor unexpected failure trả trạng thái sanitized.

### 15.2 Correlation ID

9. Server tạo ID khi request không có ID tin cậy.
10. Invalid/oversized ID bị thay thế và không phản chiếu.
11. Response header chứa ID hiệu lực.
12. Log cùng request chứa đúng ID.
13. MDC được cleanup sau success.
14. MDC được cleanup sau exception.
15. Request kế tiếp trên cùng thread không nhận ID cũ.
16. Async dispatch propagation/cleanup đúng contract.
17. Correlation ID không xuất hiện trong metric tags.

### 15.3 HTTP metrics

18. Request thành công tăng counter/timer đúng một lần.
19. `400/401/403/404/405/409/413/429/500` được phân loại theo final status.
20. Raw session ID/query string không xuất hiện trong tags.
21. Hai session ID khác nhau trên cùng route không tạo series theo ID.
22. Unmatched path được gom vào static unknown/not-found value.
23. Metrics failure không thay đổi business response.

### 15.4 Business metrics

24. Match success/failure/timeout tăng đúng outcome.
25. Session transition chỉ đếm sau mutation thành công.
26. Join/leave rejected request không ghi success.
27. Terminal `409` được phân loại đúng.
28. Token issue/renew không ghi token hoặc channel.
29. Rate-limit decision ghi đúng policy/decision một lần.
30. Blocked request không tạo downstream business-success metric.
31. Avatar validation/storage outcomes được phân loại hữu hạn.
32. Duplicate callback/retry không nhân đôi authoritative success.

### 15.5 Logging/privacy

33. Log capture không chứa password/token/cookie/CSRF/Authorization.
34. Log không chứa principal, email, user ID hoặc session ID.
35. Log không chứa raw URI/query string/filename/request body.
36. Raw SDK/database/exception message không xuất hiện.
37. Expected `4xx` không ghi stack trace hoặc duplicate error log.
38. Unknown failure ghi event/category tĩnh và exception class cho phép.

### 15.6 Regression

39. Existing Security Hardening tests PASS nếu feature có trên base.
40. Existing Lifecycle tests PASS.
41. Existing Agora JavaScript tests không bắt buộc nếu base không chứa Agora frontend; nếu base chứa thì phải PASS và SDK giữ đúng `4.24.7`.
42. Full clean test và clean build PASS.
43. Application startup/smoke PASS với test database riêng.

Không dùng `Thread.sleep` cho deterministic timing test nếu controllable clock/ticker hoặc polling assertion phù hợp hơn.

## 16. Automated gate

Sau khi tất cả slice PASS:

1. Chạy toàn bộ targeted tests.
2. Chạy:
   - `./gradlew clean test --no-daemon`, hoặc `\.\gradlew.bat clean test --no-daemon` trên Windows.
   - `./gradlew clean build --no-daemon`, hoặc `\.\gradlew.bat clean build --no-daemon` trên Windows.
3. Báo chính xác tests, failures, errors, skipped và build result.
4. Chạy:
   - `git diff --check`
   - migration immutability check
   - dependency diff/audit
   - actuator exposure audit
   - metric-cardinality audit
   - secret/privacy scan
   - logging output scan
   - changed/untracked classification
   - staged-file check
5. MySQL smoke dùng database test riêng; không dùng development database và không đưa credential vào báo cáo.

Automated gate không thay thế deployment/environment verification.

## 17. Manual/operational gate

Không cần thiết bị camera để đánh giá Observability core. Manual gate tối thiểu cần:

1. Khởi động application bằng test environment.
2. Xác minh anonymous/authorized actuator access.
3. Mô phỏng một số `2xx`, `4xx`, `409`, `429`, `5xx` an toàn và kiểm tra metrics/logs.
4. Xác minh correlation ID trên response và log.
5. Xác minh không có secret/PII/raw URI trong output.
6. Tạm ngắt test database để kiểm tra readiness, sau đó khôi phục.
7. Xác minh metrics endpoint chỉ truy cập từ audience được phép.
8. Xác minh runbook đủ thông tin để chẩn đoán nhưng không yêu cầu dữ liệu nhạy cảm.

Không gây lỗi trên development/production database để thử health behavior.

## 18. Review format

Mỗi frozen snapshot, Independent Review phải báo:

- Snapshot/hash hoặc exact diff scope.
- Files reviewed.
- CRITICAL/HIGH/MEDIUM/LOW findings.
- Actuator exposure/security evidence.
- Liveness/readiness correctness.
- Metric semantic và exact-count evidence.
- Cardinality evidence.
- Correlation-ID validation/cleanup evidence.
- Failure-isolation evidence.
- Logging/privacy evidence.
- Regression evidence.
- Migration consistency.
- Verdict: `PASS`, `FAIL` hoặc `BLOCKED`.

Review không sửa code.

## 22. Baseline audit decision record (2026-09-08)

This section is the authoritative audit disposition for the draft markers above. It records the read-only baseline and closes the open decisions in section 20. The observability implementation is still a later, separately reviewed task; this document does not authorize production-code, build-file, migration, staging, commit, or push changes.

### 22.1 Git baseline and dependency base

- Candidate dependency base: `dee456337465a2e51364f743f6a7d4111afa0af6` (`docs: specify user-friendly error handling`). Its direct ancestry includes Security Hardening implementation `8267ce934a69d18a70da1fbcb615c48e27080477` and specification `e83085245df981d755b20824bd72179eb589a806`.
- The exact merge-base with `main` is `ea1bec901816c791ff60bc2d729ce0794b2e6297`.
- `feature/agora-reliability` is not part of this candidate base. No Agora frontend dependency is assumed by this specification.
- The working tree was dirty before this audit. Dirty files are classified below and are excluded from the dependency base; no reset, revert, delete, stage, commit, or push was performed.

### 22.2 Build and observability dependency evidence

- `build.gradle` declares Spring Boot Gradle plugin `4.1.0`, dependency-management plugin `1.1.7`, and Java 17.
- Boot dependency management is authoritative. Future implementation must use `org.springframework.boot:spring-boot-starter-actuator` without a manually pinned version. The frozen compatibility evidence for this baseline is Micrometer `1.17.0`, supplied by Boot dependency management; no Micrometer version may be pinned in the application build.
- Actuator and a Prometheus registry are absent from the current build. Prometheus export is deferred; this phase specifies Micrometer-compatible contracts only and must not add a registry or exporter.
- Current logging is Spring Boot's SLF4J/Logback stack with text properties and no verified MDC/correlation filter. The implementation must establish the MDC lifecycle explicitly and retain the existing text format unless a separate decision approves JSON.

### 22.3 Closed operational decisions

- **Management topology:** use the application port initially. No separate management port or bind address is assumed until deployment evidence requires one; deployment configuration owns that later decision.
- **Exposure:** only `health` and static-safe `info` are eligible for exposure. `prometheus` remains deferred and all other actuator endpoints remain closed. Health details require an explicitly authorized internal/management audience; anonymous responses expose status only. Metrics, when implemented, require the separately approved management audience and must never be public by default. `/env`, `/configprops`, `/beans`, `/mappings`, `/heapdump`, `/threaddump`, `/loggers`, `/conditions`, `/scheduledtasks`, and `/caches` are explicitly denied.
- **Correlation-ID trust:** no trusted proxy/request-ID policy was found in the baseline. The server therefore generates a new validated `X-Correlation-ID` and does not reflect an incoming value by default. A trusted-proxy policy requires a separate security review. Accepted values are opaque ASCII `[A-Za-z0-9._-]`, at most 64 characters; blank, oversized, or invalid values are replaced, never reflected. The active ID is returned in the response and placed in MDC before logging, then removed in `finally` for success, exception, and async/error dispatch.
- **Metric availability:** standard HTTP request count/duration/status/template metrics become available only after the approved Actuator implementation. The business catalog is limited to outcomes supported by the baseline code: matchmaking/session lifecycle, Agora token operations, rate-limit decisions, avatar outcomes, and sanitized application exceptions. No metric is currently claimed as emitted by the baseline.
- **Logging retention ownership:** repository scope ends at safe event shape and configuration/template. Deployment owner is responsible for production log retention, rotation, access control, and final retention values.
- **Alert thresholds:** all thresholds below are provisional and require traffic baseline validation before production adoption.

| Signal | Provisional trigger | Window | Severity | First action |
| --- | --- | --- | --- | --- |
| Readiness | not `UP` | 5 consecutive minutes | high | inspect database/migration and dependency health |
| HTTP 5xx | >5% of requests, minimum 20 requests | 10 minutes | high | correlate route/status and inspect recent deployment |
| p95 latency | >2x seven-day baseline | 10 minutes | medium | inspect route group and saturation |
| HTTP 429 | >2x seven-day baseline for a policy | 10 minutes | medium | verify client behavior and limiter capacity |
| Matchmaking timeout/error | >2x seven-day baseline | 15 minutes | medium | inspect queue and lifecycle outcomes |
| Token issue/renew error | >1% with minimum 20 operations | 10 minutes | high | inspect Agora configuration without exposing credentials |

Alert cards are query-ready contracts, but remain `PROVISIONAL` until traffic baseline and deployment ownership are approved:

| Alert card | Query shape | Window | Severity | Action |
| --- | --- | --- | --- | --- |
| Readiness unavailable | `max_over_time(up{job="app"}[5m]) == 0` | 5m | high | inspect database/Flyway and dependency health |
| HTTP server errors | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[10m])) / sum(rate(http_server_requests_seconds_count[10m])) > 0.05` and total requests >= 20 | 10m | high | correlate normalized route/status and recent deploy |
| p95 latency | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[10m])) by (le,route)) > 2 * baseline_p95` | 10m | medium | inspect route group and saturation |
| Rate limiting | `sum(rate(app_rate_limit_decisions_total{decision="rejected"}[10m])) by (policy) > 2 * baseline` | 10m | medium | verify client behavior and limiter capacity |
| Token failures | `sum(rate(app_agora_token_total{outcome="error"}[10m])) / sum(rate(app_agora_token_total[10m])) > 0.01` and total operations >= 20 | 10m | high | inspect configuration without exposing credentials |

### 22.4 Changed and untracked ownership classification

Read-only baseline commands were run exactly as requested:

- `src/main/resources/application.properties` — **Flyway/multipart predecessor**; dirty addition is `spring.flyway.baseline-on-migrate=true`, adjacent to existing multipart limits. It is outside this observability task and was not changed.
- `docs/specs/spec_observability.md` — **observability spec**, the only permitted edit target.
- `docs/specs/spec_rubric_peer_rating.md` — **unrelated feature spec**, existing untracked work; not modified.
- `.1devtool/` — **auxiliary task metadata**, not delivery content.
- `PROJECT_ANALYSIS.md`, `ack.txt`, `delta_review_report.txt`, `review_report.txt`, `test_output.txt`, and `reply*.ps1` — **auxiliary/review/terminal artifacts**, not production source or delivery content; not modified.
- No changed or untracked production Java source, test source, build file, IDE metadata, or migration file was found.
- No file has unknown ownership after the above classification; the dirty `application.properties` change remains explicitly excluded rather than adopted as an observability baseline.

### 22.5 Migration immutability evidence

The baseline contains migrations V1 through V4. SHA-256 values verified during the read-only audit:

- `V1__initial_schema.sql`: `3C34FFAC1CCD41525A52FF55F63A0201BCDF2D481567C5109C531FA2D6746429`
- `V2__lifecycle_v2_schema.sql`: `C0E67F28107DAECA5B666ACC2DF0803C62CCC356439455A1EDCED2D2A2F03D73`
- `V3__lifecycle_v2_data_migration.sql`: `04377556DDBC2225B9D0C3CD127FE9D6BB23D1F9E08412CAE5F73ADF9BE24937`
- `V4__admin_rubric_schema.sql`: `986366E2021B6F4D716A6C11F7EFEFBB9616BE5C0AE3532F5940427B274A607E`

No migration was edited. The `spring.flyway.baseline-on-migrate` working-tree change is not incorporated into this spec's migration baseline.

### 22.6 Audit disposition

- Findings: **CRITICAL 0, HIGH 0, MEDIUM 0**. Remaining implementation work is intentionally deferred to the approved slices and is not represented as a claim that the current application already emits telemetry.
- Final specification status: **APPROVED** for implementation planning only; production implementation still requires the separate approved slices and gates.
- SHA-256 of this specification after the audit update must be recorded by the worker report and independently rechecked before any implementation slice.

### 22.7 Remediation closure

- Audit snapshot SHA-256 before recording this line: `6A39282AE2A5628005C88C37A23B74AA4A660DE301B2BA76C40155151B19AEAF`. The final post-record hash is reported with this remediation result.
- The dirty/untracked classification above is complete and ownership is explicit; no source, test, build, IDE, or migration artifact is silently adopted.
- Dependency base, Boot 4.1.0, dependency-management 1.1.7, Java 17, and Micrometer 1.17.0 are frozen as evidence. Actuator remains Boot-managed and implementation-deferred; Prometheus remains deferred.
- Same-port management, allowlisted exposure, protected health details/metrics, denylisted sensitive endpoints, server-generated correlation ID, validation, response header, MDC cleanup, metric-contract-only availability, logging sanitization/format, retention owner, and provisional query/window/severity/action alert cards are explicitly closed here.
- Migration discrepancy is documented: `AGENTS.md` says V1-V3, while the read-only baseline contains immutable V1-V4 including `V4__admin_rubric_schema.sql`; `AGENTS.md` was not changed.
- Closure verdict: **APPROVED** for implementation planning only. No CRITICAL, HIGH, or MEDIUM finding remains in the specification audit; implementation still requires its own review gates.

## 19. Packaging gate

Chỉ packaging sau:

- Spec được `APPROVED` và ghi SHA-256.
- Tất cả implementation slice PASS.
- Final automated gate PASS.
- Independent final Review PASS.
- Manual/operational gate PASS hoặc có waiver riêng, rõ phạm vi và rủi ro.
- Người dùng cấp authorization packaging riêng.

Trước mỗi commit:

- `git diff --cached --stat`
- `git diff --cached --name-status`
- `git diff --cached --check`
- `git diff --cached`

Không dùng `git add .`.

Không đưa vào delivery:

- Runtime logs hoặc terminal transcript.
- Metrics dump/scrape output.
- `.env` hoặc credential.
- Database dump.
- Heap/thread dump.
- IDE/build metadata.
- Auxiliary review report nếu không thuộc delivery.

Commit đề xuất:

1. `docs: specify application observability`
2. `feat: secure health checks and request correlation`
3. `feat: instrument application metrics`
4. `test: verify observability and telemetry privacy`

## 20. Quyết định đã chốt trước APPROVED

Các quyết định sau đã được baseline audit/review chốt trong mục 22 và không còn là blocker cho implementation planning:

1. Dependency base chính xác của `feature/observability`.
2. Spring Boot/Micrometer/Actuator version và dependency hiện có.
3. Management endpoint dùng cùng port hay port nội bộ riêng.
4. Audience/security rule cho health detail và metrics scrape.
5. Có dùng Prometheus registry ngay trong phase này hay chỉ instrument với Micrometer abstraction.
6. Trusted proxy/correlation-ID policy.
7. Route-template behavior thực tế cho unmatched/error dispatch.
8. Business metric nào khả dụng trên dependency base đã chọn.
9. Logging output format: pattern key-value hay JSON.
10. Production retention/rotation owner và policy.
11. Initial alert thresholds sau khi có baseline traffic.

Các quyết định nêu trên đã được cập nhật vào mục 22 và chuyển status sang `APPROVED`; production code vẫn bị khóa cho đến khi các implementation slice và independent review riêng đạt yêu cầu.

## 21. Tiêu chí hoàn thành

Observability chỉ được coi là hoàn thành khi:

- Health/readiness phản ánh đúng runtime và dependency state.
- Actuator surface được giới hạn và bảo vệ.
- HTTP và business metrics đúng semantics, bounded cardinality.
- Correlation ID hợp lệ, có propagation cần thiết và cleanup an toàn.
- Logging hỗ trợ điều tra lỗi mà không lộ secret/PII/domain identifier.
- Instrumentation failure không phá business behavior.
- Tests, build, audits, operational gate và independent review đều PASS.
- Không có migration ngoài spec và không có telemetry artifact nhạy cảm trong delivery.
- KhÃ´ng cÃ³ migration ngoÃ i spec vÃ  khÃ´ng cÃ³ telemetry artifact nháº¡y cáº£m trong delivery.

## 23. Accepted finding remediation disposition

The accepted HIGH/MEDIUM findings from the current project review are recorded here so
that this observability specification does not silently claim to repair production code.
Only controls explicitly marked as addressed by this document are in scope; all other
findings require their own implementation task and review.

| Finding | Severity | Remediation in this specification |
| --- | --- | --- |
| MatchMakingService is in-memory and does not scale across restarts/instances | HIGH | Not fixable in an observability spec. Metrics must report authoritative outcomes and must not create replacement queue persistence or domain mutation. |
| Matchmaking accepts client-supplied identity without authenticated-user validation | HIGH | Not fixable here. Correlation IDs and metric labels must never be used as identity or authorization, and no user/session identifier may be emitted. |
| Package name differs from the target architecture | MEDIUM | Not fixable here. The current package remains authoritative per AGENTS.md section 2; no package refactor is authorized. |
| Hardcoded UID/tag in the video-call page | MEDIUM | Not fixable here. No raw UID, tag, session, or client input may become a log field or metric label. |
| Tag system is not integrated into matchmaking | MEDIUM | Not fixable here. No tag or domain identifier is added to the observability label catalog. |
| No global exception handler | MEDIUM | Addressed as an observability contract only: application-exception metrics use bounded categories and final HTTP status; raw exception text is never exposed. Adding a handler remains separate. |
| Agora UID uses a database Long and may overflow the SDK range | MEDIUM | Not fixable here. Agora telemetry may report only bounded operation/outcome values and must never expose or label a UID. |
| No rate limiting or abuse protection | MEDIUM | Addressed as an observability contract only: section 10 defines one bounded rate-limit decision counter, and sections 12/16 define provisional 429 alert/runbook evidence. Implementing the limiter remains separate. |
| CSRF handling for WebSocket/video-call actions needs consistency review | MEDIUM | Not fixable here. Observability must preserve existing authentication/authorization and must not introduce an actuator or telemetry backdoor. |
| WebSocket allows every origin | MEDIUM | Not fixable here. Origin policy belongs to security configuration; telemetry must not weaken it or record raw origin values. |
| Duplicate Agora UID concern (direct User.id use) | MEDIUM | Not fixable here. Token metrics remain operation/outcome only and contain no UID or domain identifier. |
| Username uniqueness is not validated during profile update | MEDIUM | Not fixable here. Logs and metrics must not contain username, email, principal, or profile identifiers. |
| Session-disconnect cleanup can race with endCall() | MEDIUM | Addressed as an observability contract only: section 10.3 requires authoritative, once-only business-success counting; lifecycle synchronization remains separate. |

This disposition does not change production implementation, security behavior, domain
semantics, dependency set, migrations, or packaging gates.
## 24. Authoritative accepted-finding remediation

This section supersedes any inferred finding list. The accepted remediation set for this
specification is exactly the following three findings; the specification is the only
permitted edit target.

### 24.1 HIGH — Flyway baseline-on-migrate working-tree setting

The working-tree setting `spring.flyway.baseline-on-migrate=true` in
`src/main/resources/application.properties:44` is not accepted as an observability
implementation default. It must be removed or rejected before implementation delivery.
The implementation must not modify migration files, datasource configuration, or schema
history to accommodate it. A baseline operation, if ever required for an existing
deployment, must be an explicitly controlled deployment action with a reviewed target
database and verified V1–V4 migration state; it must never silently baseline an existing
database and skip migrations. The readiness contract must continue to fail safely when
migration compatibility or required schema is not verified.

Acceptance criteria:

- The observability implementation does not enable `baseline-on-migrate` by default.
- No migration or datasource/config file is changed by this remediation.
- A deployment runbook records explicit approval, target-database verification, and
  post-baseline migration/schema checks for any exceptional baseline operation.

### 24.2 MEDIUM — Client handling of HTTP 429

Token/join clients must classify HTTP `429 Too Many Requests` as a retryable throttle
outcome, distinct from camera or generic failure. They must read and honor a valid
`Retry-After` response value, bound the delay when a local safety limit is required, and
use bounded backoff with jitter. They must not retry immediately, retry indefinitely, or
create a retry storm. `leave-agora` must not silently discard a 429: it must surface a
throttled/retryable outcome and follow the same bounded retry policy where retry is safe.
The server-side rate-limit metric remains exactly one decision per request as defined in
section 10.

Acceptance criteria:

- 429 from token and join is rendered as throttled/retryable, never as a camera error.
- A valid `Retry-After` value controls the earliest retry; missing/invalid values use
  bounded exponential backoff with jitter.
- Leave responses, including 429, are observed and do not trigger unbounded retries.
- Tests cover token, join, and leave 429 handling, `Retry-After`, invalid/missing header,
  bounded attempts, and no retry storm.

### 24.3 MEDIUM — Rate-limit request-path resolution

`RateLimitRequestResolver` must not compare a raw `request.getRequestURI()` directly with
application routes. It must strip the servlet context path first, then apply the same
route normalization used by the server mapping. Encoded, semicolon/path-parameter, and
other ambiguous path variants must be rejected or normalized deterministically before
policy matching; they must not create an alternate path that bypasses rate limiting.
The resolver must preserve bounded policy labels and must never emit the raw path as a
metric label or log field.

Acceptance criteria:

- Matching is identical with and without a non-empty servlet context path.
- Encoded and semicolon/path-parameter variants are covered by an explicit reject or
  canonicalization rule and cannot bypass the intended policy.
- Tests cover context-path and path-variant cases, including encoded separators and
  ambiguous forms accepted by the underlying server mapping.
- Unknown/ambiguous input resolves to a bounded `unknown`/rejected outcome, never to an
  unbounded policy label derived from request input.

This section authorizes documentation/specification changes only. It does not authorize
edits to `application.properties`, `video_call.js`, `RateLimitRequestResolver.java`,
tests, migrations, build files, or any other production/configuration file.
