# Deployment Hardening Specification

**Status:** PROPOSED
**Independent Review:** PENDING for final metadata delta
**Review findings remaining:** none subject to final metadata delta Review
**Executable authority:** `build.gradle`
**Spring Boot:** `4.1.0`
**Java toolchain:** `17`
**Scope:** deployment packaging and operating contract only; implementation is not authorized by this document.

**Original approved pre-topology baseline SHA-256:** `B93C6CEF3CB14ADD7A5BB54175EFD620D502B425895F1D56D04E69E539417947`
**Provenance:** externally supplied/frozen pre-delta workspace baseline; not yet committed.
**First bounded-downtime proposed snapshot SHA-256:** `8EE17CB946B4F517B87C5B2F6AC51125929106282CA5679934625B27BBA02920`
**First snapshot classification:** frozen pre-clarification delta workspace baseline reviewed read-only; superseded, not approved.
**Clarified proposed snapshot SHA-256:** `83530937AC718B7CC26235656414C6999B654AF270AFF6C617509F6CD76E973F`
**Clarified snapshot classification:** reviewed candidate before final metadata repair; superseded by this metadata-only candidate.
**Final candidate SHA-256:** computed by Lead after writer completion; not embedded as authoritative current hash.
**Final candidate status:** PROPOSED until independent metadata delta Review PASS.
**Previous current spec SHA-256:** `83530937AC718B7CC26235656414C6999B654AF270AFF6C617509F6CD76E973F` (history; superseded by this metadata-only candidate)
**Open-finding history:** previous severity HIGH; resolution RESOLVED / CLARIFIED. Pre-mutation gate validates artifact/config preparedness; runtime readiness only after rollback starts; bounded downtime authorized; no zero-downtime/parallel-runtime guarantee.

## 1. Decisions and boundaries

The target is a demo, single-VPS deployment operated through Server Compass. Docker Compose runs the application and a MySQL container on the same VPS. This is not an HA or multi-node design. MySQL is reachable only on a private Docker network, its port 3306 is never published, and its data is stored in a named volume. Secrets are entered at the VPS/Server Compass boundary; local `.env` files are never read, copied, uploaded, or committed.

Out of scope: real deployment, real credentials, `.env` inspection/upload, Kubernetes, managed MySQL, HA/multi-node operation, Peer Rating, Admin/Rubric changes, Agora reliability changes, changing V1–V4, and production-code, test, workflow, or build changes during this spec phase. No stage, commit, push, or deploy is part of this task.

The implementation must preserve the current package and application contracts. The repository currently uses `com.example.videocall_marching_language`, Spring Security/Google OIDC, persistent presence/matchmaking, Agora session tokens, Cloudinary avatar storage, WebSocket recovery, scheduler/finalizer behavior, and Flyway migrations. The approved executable authority is `build.gradle`, with Spring Boot `4.1.0` and Java toolchain `17`; implementation must not silently change them.

## 2. Current-state inventory and environment contract

The current `application.properties` uses MySQL, `ddl-auto=validate`, Flyway, Google OAuth, Cloudinary, Agora, and `WEBSOCKET_ALLOWED_ORIGIN_PATTERNS`. Its datasource URL currently contains `localhost`; production must replace that with the Compose service name. No deployment artifact (`Dockerfile`, `compose.yaml`, or `.dockerignore`) currently exists.

“Existing” below means the name is referenced by current application code/configuration. “Add” means it is a deployment contract that must be introduced by the deployment implementation; this does not authorize adding it during the spec phase.

| Name | State | Meaning | Classification |
|---|---|---|---|
| `MYSQL_DATABASE` | Add | MySQL database/schema name used by the container and JDBC URL | Required, non-secret config |
| `MYSQL_USERNAME` | Existing | Application datasource username | Required, secret/config classification |
| `MYSQL_PASSWORD` | Existing | Application datasource password | Required secret |
| `MYSQL_ROOT_PASSWORD` | Add | MySQL container bootstrap/administrative password | Required secret; never used by the app |
| `GOOGLE_CLIENT_ID` | Existing | Google OAuth client identifier | Required non-secret config |
| `GOOGLE_CLIENT_SECRET` | Existing | Google OAuth client secret | Required secret |
| `CLOUDINARY_CLOUD_NAME` | Existing | Cloudinary cloud identifier | Required non-secret config |
| `CLOUDINARY_API_KEY` | Existing | Cloudinary API credential | Required secret-classified credential |
| `CLOUDINARY_API_SECRET` | Existing | Cloudinary API secret | Required secret |
| `AGORA_APP_ID` | Existing | Agora project identifier; safe to expose only through the existing browser contract | Required non-secret config |
| `AGORA_APP_CERTIFICATE` | Existing | Server-side Agora token signing credential | Required secret |
| `WEBSOCKET_ALLOWED_ORIGIN_PATTERNS` | Existing | Explicit comma-separated production origin allowlist | Required non-secret policy config; no localhost fallback in production |
| `APP_BASE_URL` | Add | Canonical HTTPS public origin, including scheme and host, used for OAuth and browser URLs | Required non-secret deployment config |
| `SPRING_PROFILES_ACTIVE` | Add | Selects the production profile | Required non-secret deployment config |
| `SERVER_PORT` | Add | Internal application listen port, supplied by environment | Required non-secret deployment config |

Production validation must fail before traffic if any required name is missing, blank, unresolved, or invalid. No value may appear in logs, health responses, image layers, build output, backup output, or error messages. Local-only defaults are not production defaults.

## 3. Spring Boot production profile

The production profile shall:

1. Run on Java 17 and read `SERVER_PORT` rather than hard-coding the external port.
2. Build the JDBC URL from the Compose MySQL service name (for example, `mysql`) and `MYSQL_DATABASE`; `localhost` is forbidden in the production datasource URL.
3. Keep Flyway as the sole schema owner and keep Hibernate at `ddl-auto=validate`.
4. Enable graceful shutdown with a finite deadline sufficient for HTTP requests and WebSocket/session cleanup. Shutdown must mark readiness unavailable before closing listeners.
5. Enable trusted forwarded headers only for the bounded reverse-proxy boundary. The application must not trust arbitrary client-supplied `Forwarded` or `X-Forwarded-*` headers.
6. Configure the authentication/session cookie as `Secure`, `HttpOnly`, and an explicitly selected `SameSite` policy compatible with the Google callback and browser flow. The selected policy must be reviewed with the HTTPS topology.
7. Require explicit production WebSocket origins. Production startup/deployment validation must reject empty, wildcard, localhost, loopback, or development-only origin patterns.

The proxy-facing application must not be directly published to the Internet. `APP_BASE_URL` must be the canonical HTTPS origin, with no hard-coded domain.

## 4. Container artifacts

### Dockerfile

Create a multi-stage Dockerfile with Java-17-compatible, pinned builder and runtime images. The builder must invoke the Gradle Wrapper, run the clean production build, and retain only the executable Spring Boot JAR as the runtime input. The runtime image must:

- contain no source tree, Gradle cache, wrapper, tests, reports, `.env`, or auxiliary credentials;
- run as a dedicated non-root user with only the permissions required to read the JAR and use an explicitly permitted temporary directory;
- use an exec-form entrypoint such as `java -jar /app/app.jar`, so Java receives Compose/Server Compass `SIGTERM` directly;
- expose only the documented internal application port; `EXPOSE` is documentation, not access control; and
- pin base image references by immutable digest during implementation.

### .dockerignore

Exclude `.env` and `.env.*`, `.git`, `.1devtool`, `build`, `.gradle`, IDE metadata, logs, dumps, reports, transcripts, test output, generated artifacts, and every `reply*.ps1`. The ignore rules must prevent secrets and workspace transcripts from entering Docker build context.

### compose.yaml

Define `app` and `mysql` services with:

- a private application/DB network; no `mysql` `ports` mapping and never any published `3306`;
- a named MySQL data volume;
- a MySQL healthcheck using the container’s configured health mechanism without printing credentials;
- `app.depends_on.mysql.condition: service_healthy`, plus application/JDBC retry behavior so health ordering is not treated as connection reliability;
- restart policies appropriate for a demo VPS, bounded to avoid an unrecoverable restart storm;
- explicit, reasonable app resource limits for a demo VPS (initial contract: 2 CPU and 1 GiB memory maximum, subject to VPS capacity review); and
- no hard-coded credentials, secret values, or public application port.

Only the Server Compass reverse proxy may reach the production app. The app may attach to a narrowly scoped proxy-facing network with no host port publication; MySQL attaches only to the private DB network shared with the app. The Compose topology must not claim HA, failover, or zero-downtime guarantees.

## 5. Migration safety

V1–V4 are immutable. Their verified SHA-256 values are:

| Migration | SHA-256 |
|---|---|
| `V1__initial_schema.sql` | `3C34FFAC1CCD41525A52FF55F63A0201BCDF2D481567C5109C531FA2D6746429` |
| `V2__lifecycle_v2_schema.sql` | `C0E67F28107DAECA5B666ACC2DF0803C62CCC356439455A1EDCED2D2A2F03D73` |
| `V3__lifecycle_v2_data_migration.sql` | `04377556DDBC2225B9D0C3CD127FE9D6BB23D1F9E08412CAE5F73ADF9BE24937` |
| `V4__admin_rubric_schema.sql` | `986366E2021B6F4D716A6C11F7EFEFBB9616BE5C0AE3532F5940427B274A607E` |

An empty MySQL database must migrate in order through V4. Flyway checksum validation must fail startup on drift; `baseline-on-migrate` is forbidden because it can hide drift. Before applying any new migration, an operator must produce and verify a backup. An absent or unverifiable pre-migration backup blocks deployment. Application rollback never auto-rolls back the database: use a backward-compatible app version, a reviewed forward-fix migration, or an explicitly approved restore procedure.

## 6. Health and runtime behavior

Use the existing liveness/readiness capability as a current-code reference. The canonical paths are `/actuator/health/liveness` and `/actuator/health/readiness`; responses must be sanitized and must not expose credentials, datasource secrets, SQL, stack traces, provider payloads, or PII. Public exposure is limited to approved health/info endpoints.

Liveness represents process health and must not fail only because MySQL or Flyway is temporarily unavailable. Readiness must remain failing while application startup, datasource, or Flyway validation/migration is unavailable. A finite startup grace period must prevent premature restart during normal image/database startup. A graceful shutdown deadline must flip readiness before termination. Non-essential dependencies must not create a restart loop or make liveness flap; required DB/Flyway failure must prevent readiness and traffic.

## 7. Server Compass and reverse proxy

The reverse proxy shall:

- terminate HTTPS and redirect HTTP to HTTPS;
- derive the public domain from the operator-supplied `APP_BASE_URL`, not from a hard-coded value;
- route WebSocket Upgrade/Connection headers to the app, with an explicit idle timeout compatible with calls;
- trust forwarded scheme/host/client headers only from the proxy boundary;
- enforce the avatar upload limit consistently with the current application limits (5 MB file, 6 MB request, and a proxy limit at least as large but finite); and
- publish no Actuator endpoint other than approved sanitized health/info routes.

The app must have no public host port. Proxy host allowlisting, TLS certificate/renewal, trusted proxy network, upload limit, WebSocket timeout, and health-route exposure must be recorded in Server Compass configuration and smoke-tested.

## 8. OAuth and browser security

The production Google callback must be exactly `${APP_BASE_URL}/login/oauth2/code/google`, after resolving the supplied canonical HTTPS base URL. No domain may be invented or hard-coded by the implementation. Google Console registration and application configuration must match exactly, including scheme, host, and path.

Camera and microphone access must be tested only through the HTTPS origin because browser media permissions require a secure context. The smoke test must verify Secure/HttpOnly/SameSite cookies, correct forwarded HTTPS behavior, authenticated WebSocket origin enforcement, and sanitized OAuth/provider errors.

## 9. Backup and restore

Run periodic MySQL backups outside the main app container and outside the live MySQL data volume (for example, a VPS-host backup destination with restricted permissions). For the demo, retain at least the latest seven daily backups and one weekly backup, subject to disk-capacity review. The backup process must:

- use a least-privilege database account where possible;
- never commit, echo, print, or log passwords or connection secrets;
- write sanitized success/failure metadata only;
- encrypt or otherwise protect backup files at rest according to the VPS policy;
- verify backup completion and integrity; and
- restore periodically to a separate disposable MySQL test instance, then run migration/schema and application-read checks.

Before every new migration, the deployment operator must show a successful backup and restore/integrity check appropriate to the release. Missing evidence blocks deployment. Restore is an operator action; the app must not automatically destroy or downgrade schema during rollback.

## 10. CI and verification gates

The implementation is not release-ready until all applicable gates pass on a clean checkout and without production secrets:

1. `./gradlew clean test` and `./gradlew clean build` using Java 17 and the Gradle Wrapper.
2. Docker image build from the pinned commit and inspection proving only the executable JAR/runtime content is present.
3. `docker compose config` validation with secret placeholders only; no credentials in rendered output.
4. Secret/content scan covering repository, build context, image layers, logs, reports, transcripts, and generated artifacts.
5. Migration test on a separate empty MySQL database: apply V1–V4, verify the final schema, and verify checksum drift fails startup.
6. Container startup with MySQL health ordering, application retry behavior, startup grace, and no restart loop.
7. Liveness/readiness checks, including DB/Flyway-unavailable readiness failure and sanitized responses.
8. Graceful shutdown test verifying readiness drains before SIGTERM completion and WebSocket/session cleanup receives the configured deadline.
9. HTTPS browser smoke through the reverse proxy: redirect, OAuth callback, cookie flags, camera/microphone secure context, avatar upload boundary, WebSocket upgrade/origin, and authenticated critical path.
10. Regression of the existing health/redaction behavior and all current application tests.

Any skipped, unexecuted, or zero-result gate is `BLOCKED`, not `PASS`.

## 11. Deployment and rollback flow

This is an explicitly authorized **OPTION 2 bounded-downtime in-place replacement** for the single-VPS, non-HA topology. The app container is stopped and recreated in place; bounded downtime is allowed during stop, recreate, start, health gating, and rollback. This contract provides no HA, zero-downtime, blue-green, or traffic-level atomic cutover guarantee, and it must not invent blue-green deployment or route switching.

The following finite bounds are mandatory and are part of the deployment contract: trusted migration-verification timeout `60s`; candidate startup/readiness bound `120s`; candidate health-gate bound `60s` with at most `12` checks at `5s` intervals; rollback startup/readiness and health bound `120s` total with at most `12` checks at `10s` intervals; and no more than `1` candidate attempt followed by `1` rollback attempt. A timeout, non-zero exit, signal termination, malformed result, or exhausted retry count is failure. No unbounded retry, loop, or implicit success is permitted. The implementation must invoke these bounds rather than merely document them.

1. Deploy staging/demo first and complete the full smoke suite. Pin the exact Git commit and candidate image; do not package untracked workspace files. Inject approved secrets at the VPS/Server Compass boundary; never read or upload a local `.env`.
2. **Pre-mutation rollback-preparedness:** before the candidate is stopped, validate the pinned candidate identity, Compose configuration, required production preconditions, and all rollback-preparedness inputs: the previous image reference exists and is inspectable, nonempty, and a well-formed digest/tag; the required Compose configuration renders; immutable configuration and deployment scripts exist; network, volume, and database prerequisites resolve; and trusted rollback inputs exist. This is artifact/configuration readiness only, not runtime health validation and not preservation of the current release. If any check fails, stop before mutation: no app stop, recreate, mutation, or promotion may occur.
3. **Trusted migration verification:** `deploy.sh` must execute the repository-local `deploy/verify-migrations.sh` afresh, directly from the pinned/trusted repository content, before any app stop, recreate, mutation, or promotion. The result must be captured and validated against the verifier's exact success contract. A caller-supplied verifier, caller-supplied evidence, stale evidence, cached result, skipped invocation, malformed result, signal termination, timeout, or non-zero exit is rejected deterministically and blocks the deployment; none may bypass the fresh trusted invocation.
4. **Backup/preconditions:** verify the pre-migration backup gate and integrity evidence as required, pull/build the candidate from the pinned commit, and start MySQL or confirm it is healthy. Any missing, unverifiable, timed-out, malformed, signaled, or failed precondition blocks the deployment before app replacement. The database must not be automatically downgraded.
5. **Stop/recreate:** remove candidate traffic at the reverse-proxy boundary as applicable, then stop and recreate the app container in place. This is the bounded-downtime replacement point; no traffic-level atomicity is implied.
6. **Start candidate:** start the candidate with the production profile and require readiness within the finite startup/readiness bound; liveness alone is insufficient.
7. **Bounded health gate and promotion:** run the bounded readiness/health gate, then the required HTTPS/browser smoke suite and inspect only sanitized logs/health output. Candidate success and in-place promotion are valid only after the health gate passes (and the applicable smoke gates pass); any health failure, timeout, signal, malformed result, or non-zero smoke result fails the candidate.
8. **Runtime rollback-health:** only after candidate failure and rollback begins, stop and clean up the failed candidate container/resources created by this attempt, retain required evidence, and start the validated previous image in place. The previous target cannot be runtime health-checked before the current replacement in this approved single-instance topology; runtime readiness occurs only after the previous image starts within the bounded rollback window. Run the bounded rollback startup/readiness/health check and re-run the required smoke suite. Report `ROLLBACK PASS` only after the rollback health check passes (and the applicable smoke gates pass). If rollback startup/readiness/health/smoke is unhealthy, timed out, signaled, malformed, or non-zero, enter the exact terminal state `DEPLOYMENT FAILED ROLLBACK FAILED`; do not report false recovery, retry indefinitely, or attempt a migration downgrade. Perform deterministic cleanup and retain sanitized diagnostics for every terminal failure. Do not automatically roll back the database; use a backward-compatible app version, a reviewed forward-fix migration, or an explicitly approved restore procedure.
9. If the candidate or deterministic rollback succeeds, record the deployed commit/image and sanitized backup/migration evidence. A deployment with neither candidate health success nor healthy rollback is terminal failure.

The previously identified MEDIUM concern—single-VPS in-place replacement can cause bounded service interruption—is recorded and **AUTHORIZED BOUNDED-DOWNTIME TOPOLOGY TRADEOFF** for this option. It is not silently removed, and it does not authorize HA, zero downtime, blue-green deployment, route switching, or a weaker rollback/migration contract.

Real deployment, credentials, and infrastructure changes remain outside this spec-writing task.

## 12. Open pre-deployment validation gates and assumptions

The following remain open pre-deployment validation gates, not review findings:

- Server Compass reverse-proxy product, external proxy network name, VPS size, TLS certificate/renewal owner, backup destination/encryption, and exact demo maintenance/retention window remain operational choices; they must be recorded before implementation while preserving all constraints above.
- The initial 2 CPU/1 GiB app limit and seven-daily/one-weekly retention are explicitly proposed demo defaults requiring validation before deployment, not an HA or capacity guarantee.
- `MYSQL_DATABASE`, `APP_BASE_URL`, `SPRING_PROFILES_ACTIVE`, and `SERVER_PORT` are deployment additions; their values are intentionally unspecified.

**Review verdict:** DELTA REVIEW REQUIRED. The previous approval remains the frozen baseline; this bounded-downtime delta is not approved until independent Spec Delta Review passes and Lead freezes it. The listed operational decisions remain pre-deployment validation gates.
