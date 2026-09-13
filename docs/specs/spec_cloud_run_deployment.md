DRAFT  CLOUD RUN READINESS NOT ASSESSED

# Cloud Run Phase 3 Deployment Contract

**Target platform:** `TARGET_PLATFORM=GOOGLE_CLOUD_RUN`
**Scope:** contract and readiness review only. No implementation, resource creation, image build/push, migration execution, deployment, traffic assignment, staging, commit, push, or packaging is authorized by this document.
**Environment:** staging first; production readiness is not evaluated.
**Branch observed:** `feature/deployment-hardening`
**Independent contract review:** REQUIRED before `APPROVED`
**User authorization:** REQUIRED separately before implementation or any Google Cloud resource creation.

## 1. Authority, boundaries, and verified baseline

This is a separate Cloud Run contract. The existing VPS Phase 3 artifacts and `docs/specs/spec_deployment_hardening.md` are frozen and superseded as the deployment target; they are not a PASS for Cloud Run and must be preserved exactly. Existing VPS scripts, Compose, Dockerfile, and reverse-proxy material must not be mechanically reused.

The actual repository was inspected for this draft. Verified facts include:

- The package is `com.example.videocall_marching_language`.
- `build.gradle` currently declares Spring Boot `4.1.0`, Java toolchain `17`, Actuator, JPA, MySQL, Flyway, OAuth2 client, WebSocket, Cloudinary, Agora, and `springboot4-dotenv`.
- `application-prod.properties` already sets graceful shutdown, forwarded-header handling, secure/HttpOnly/Lax session cookies, `ddl-auto=validate`, MySQL TLS intent, Flyway enabled, and an `APP_BASE_URL` OAuth redirect template.
- The current production profile binds `server.port` from `SERVER_PORT`; it does not bind the Cloud Run-injected `PORT`. This is a current repository mismatch and a serving blocker until the future implementation explicitly maps the Cloud Run contract to the application.
- The current production profile has `spring.flyway.enabled=true`; Spring Boot therefore auto-runs Flyway during serving application startup. This is a current serving-migration blocker, not merely an unverified acceptance item.
- No explicit Hikari maximum pool, minimum idle, acquisition timeout, connection timeout, validation/keepalive setting, or lifetime is present in the inspected properties. Pool behavior and the instance-to-database connection budget are therefore unproven.
- `application.properties` sets `logging.level.org.hibernate.SQL=INFO` and does not prove SQL/bind-value sanitization. The sanitized-log requirement remains unverified until implementation and tests demonstrate it.
- The current Actuator security configuration exposes only health/info endpoints publicly; `/actuator/health/liveness` is process/liveness-oriented and `/actuator/health/readiness` includes database and Flyway health. The existing tests require sanitized public responses and `OUT_OF_SERVICE` readiness on dependency failure.
- The current Dockerfile uses Linux Java 17 builder/runtime images pinned by digest, a dedicated non-root user, an exec-form Java entrypoint, and no host filesystem declaration. This is evidence for review, not proof that the image is Cloud Run-ready.
- The current Compose file defines an app plus MySQL container and a named MySQL volume. That topology is VPS-only and is not the Cloud Run database design.
- Compose also declares `MYSQL_ROOT_PASSWORD` for the VPS-only MySQL container health check. That root credential/container pattern is forbidden in the Cloud Run contract and must not be carried into Cloud Run configuration.
- The current production datasource uses the Compose hostname `mysql`; Cloud Run must replace this with the selected Cloud SQL connectivity contract.
- The target database identity and database user are different contract fields: target identity means the environment's selected Cloud SQL instance and database/schema; a database user is a credentialed MySQL principal. Runtime and migration principals MUST be separate users, with separate runtime and migration service accounts where the selected connectivity/IAM mechanism supports them. Neither is MySQL root.
- V1–V4 are present and immutable for this phase. V3 explicitly documents that its data transformation is not easily reversible.

### Non-authority and immutable items

This document does not authorize changes to the VPS spec, Phase 1/2 artifacts, V1–V4, source, build configuration, Dockerfile, Compose, scripts, `.env`, `.env.local`, or application configuration. A future implementation slice must first name every permitted file and obtain authorization.

## 2. Explicit platform decision

`TARGET_PLATFORM=GOOGLE_CLOUD_RUN`.

Persistent MySQL requires Cloud SQL for MySQL or a separately approved external MySQL service. A MySQL container is not permitted in a Cloud Run service, and a Cloud Run instance's local filesystem is not a database or durable file store.

Cloud Run provides an HTTPS `*.run.app` service URL. DuckDNS is out of scope and is not needed initially. A custom-domain migration may be planned later, but it must not be mixed into the first Cloud Run gate.

Cloud Run compute may have a free tier; Cloud SQL, storage, logging, Artifact Registry, Secret Manager, networking/connectivity, and Cloud Run Jobs can still incur charges. No zero-cost claim is permitted. See [Cloud Run pricing](https://cloud.google.com/run/pricing).

## Decision Matrix — explicit user decisions

The table below records the selected staging contract defaults and the confirmations required before resource creation. These values are deterministic contract targets, not authorization to create resources. A change requires a contract delta review.

| Decision | Minimum staging option | Cost/risk | Production upgrade path | Recommendation | User decision required |
|---|---|---|---|---|---|
| Region | One supported Cloud Run/Cloud SQL region selected for user proximity and co-location | One region is simpler and usually lower-latency; a poor choice increases latency or later migration cost | Keep co-located for production, or plan a deliberate regional migration/DR design | Choose one supported co-located region after checking users, Cloud SQL, and organization policy | Name the staging region and confirm whether production will share it |
| Monthly budget + alert thresholds | A fixed monthly planning budget with alerts at an early-warning percentage and a higher escalation percentage; no hard-cap assumption | Alerts notify but do not cap spend; SQL, storage, backups, logs, registry, networking, and Jobs can continue charging | Separate staging and production budgets, recipients, expiry, and residual-cost checks | Use a small fixed staging budget with two alert thresholds and a teardown date | Provide currency, monthly amounts, alert thresholds, recipients, expiry date, and staging/prod scope |
| Cloud Run CPU/RAM/concurrency/min/max | Smallest supported CPU/RAM that passes startup/upload tests; low concurrency; `min=0`; a finite max | Smaller resources reduce cost but increase cold-start/OOM risk; max instances also bounds SQL pressure | Re-benchmark and raise CPU/RAM/concurrency/max only with SQL pool math and load evidence | Start with the smallest supported profile that passes tests and a conservative finite max | Select CPU, memory, concurrency, min instances, max instances, and request timeout |
| Cloud SQL MySQL version/tier/storage | A currently supported MySQL version; smallest accepted staging tier; smallest accepted storage with an explicit autogrow choice | Older/smaller tiers reduce cost but constrain support, performance, and connections; storage growth can create surprise cost | Upgrade tier/storage/version through a tested, reversible-compatible change plan | Use a supported version compatible with the repository and the smallest tier/storage that passes measured workload | Select exact MySQL version, tier, storage size, autogrow policy, and maintenance window |
| HA/backup/PITR | Staging may accept no HA with automated backups and a documented PITR decision, subject to recovery-test approval | Lower cost has higher outage/data-loss risk; PITR/retention/storage cost extra | Production requires an explicit RPO/RTO decision, HA/backup/PITR retention, and restore rehearsal | Enable the minimum backup/PITR posture needed for the accepted staging RPO; do not imply production safety | Choose HA, backup schedule/retention, PITR, RPO/RTO, and accepted staging risk |
| Public IP + Cloud SQL connector vs private networking | Choose either Cloud SQL public IP through the supported Java connector/Auth Proxy path, or private IP with Direct VPC egress; no hand-written unverified path | Public path is simpler but exposes a public endpoint; private path reduces exposure but adds VPC setup, egress, and cost/complexity | Production should retain the approved path with hardened IAM/TLS, or execute a planned connectivity migration | Prefer the least-complex supported path that the user accepts after Java compatibility, TLS, failure, and cost review | Select public-IP connector/Auth Proxy or private-IP/Direct VPC egress and approve its Java connection implementation |
| IAM/service accounts | Separate staging runtime and migration service accounts, least-privilege Cloud SQL/Secret Manager access, and a separately controlled deployer | More identities add setup/rotation work but limit blast radius; combined identities increase privilege risk | Separate staging/prod identities and rotate/recertify runtime, migration, and deployer access | Keep runtime, migration, and deployment authorities separate | Confirm identity separation, exact roles, project boundaries, and who may deploy/run migrations |
| Hikari pool budget | Explicit finite pool settings with a conservative per-instance maximum and `max_instances × max_pool_per_instance` below the approved DB budget, reserving migration/admin capacity | Too small limits throughput; too large exhausts Cloud SQL connections and causes cascading failures | Recalculate after production max-instance/load changes and tier upgrades | Choose pool values from measured staging load and the selected Cloud SQL connection budget | Provide max/min-idle/timeouts/lifetime, max instances, migration-job allowance, admin reserve, and approved total DB connection budget |

### Decision closure for staging

The selected minimum staging target is: region `asia-southeast1`; planning budget USD 25/month with alerts at 50% and 80%, a named recipient, and an expiry date; Cloud Run 1 vCPU, 1 GiB, concurrency 20, request timeout 300 seconds, minimum instances 0, maximum instances 3; supported MySQL 8.x on the smallest supported staging tier with 10 GiB SSD and autogrow disabled initially; HA disabled, automated backups enabled, and PITR disabled unless the restore requirement justifies it. These are deterministic contract targets, not authorization to create resources. The user must confirm them before creation; a change requires a contract delta review.

The authoritative staging connectivity choice is Cloud SQL public IP with the supported Cloud SQL Java Connector and encrypted transport. No Auth Proxy sidecar or private-networking path is authoritative for this staging contract. The target database identity is the staging Cloud SQL instance/database tuple (proposed names `video-call-staging-sql` and `video_call_staging`); it is distinct from MySQL users `app_runtime` and `app_migrator`. Cloud Run service accounts are `cr-staging-runtime`, `cr-staging-migrator`, and `cr-staging-deployer`. Runtime and migration principals are separate and neither is root. Baseline IAM is `roles/cloudsql.client` for runtime and migration identities, per-secret `roles/secretmanager.secretAccessor` only for required secrets, and minimum Cloud Run deploy/traffic permissions for the deployer. Exact resource-level bindings remain implementation acceptance checks.

The staging Hikari target is `maximumPoolSize=5`, `minimumIdle=1`, with finite acquisition, connection, validation, keepalive, and lifetime settings selected during implementation. With three maximum serving instances, the runtime ceiling is `3 × 5 = 15` connections; additional capacity must be reserved for the migration Job, administration, and platform overhead. The implementation must fail closed if the selected tier cannot support that total.

These decisions close the contract ambiguity, but do not claim repository readiness. The current PORT, serving Flyway auto-run, Hikari, SQL-log, and Cloud SQL configuration gaps remain implementation/readiness gaps.

## 3. Container contract

### Required behavior

The serving container MUST:

1. Listen on `0.0.0.0` and the port supplied by the Cloud Run `PORT` environment variable. The application must not depend on a fixed external port. TLS terminates at Cloud Run; the container speaks the approved internal HTTP protocol. See the [Cloud Run container runtime contract](https://cloud.google.com/run/docs/container-contract).
2. Run as Linux `amd64` unless a later platform review approves another architecture and verifies every base image and native dependency. Architecture is OPEN until the build manifest is inspected.
3. Run as a dedicated non-root user if the final base image and Java runtime permit it. Any exception requires a written risk acceptance.
4. Treat the local filesystem as ephemeral. No user uploads, database files, session state, or other durable data may be written there. Temporary files must be bounded, used only where required by the existing upload/provider flow, and cleaned after use. The exact temporary-storage budget and cleanup test are OPEN.
5. Contain no Docker socket, privileged mode, host volume, host PID/network dependency, background daemon, or process supervisor. The process tree must have one foreground application process.
6. Receive SIGTERM through an exec-form entrypoint, stop accepting new work, mark readiness unavailable, allow bounded HTTP/WebSocket/session cleanup, and exit before the approved termination deadline. The current 30-second Spring lifecycle setting is evidence, not a Cloud Run-approved deadline.
7. Emit sanitized structured logs. The current repository's SQL logger is `INFO`, so this requirement is currently unproven and is a blocker until the future implementation disables SQL/bind logging for serving and tests prove sanitization. Logs must not contain secrets, OAuth codes/tokens, cookies, datasource URLs, SQL statements or bind values, provider payloads, stack traces containing credentials, request bodies, dumps, or unnecessary PII. Correlation IDs may be retained only under the existing privacy policy.
8. Contain no secrets, `.env` files, credentials, dumps, build caches, source transcripts, or generated reports in source layers or runtime layers. The final artifact must be referenced by immutable image digest.

### Resource proposal — decision required

These are conservative staging proposals only and are not selected values:

| Setting | Staging proposal | Status / acceptance |
|---|---:|---|
| CPU | `1` vCPU | DECISION REQUIRED; verify supported value and workload benchmark |
| Memory | `1 GiB` | DECISION REQUIRED; verify Java heap/headroom and upload behavior |
| Concurrency | `20` requests/instance | DECISION REQUIRED; include WebSocket/session behavior and DB pool math |
| Request timeout | `300s` | DECISION REQUIRED; verify Cloud Run maximum/support and application request needs |
| Minimum instances | `0` | PROPOSED; accept cold-start/OAuth impact or choose another value |
| Maximum instances | `3` | PROPOSED ceiling pending SQL connection budget and quota review |
| Execution environment | current supported Cloud Run generation | DECISION REQUIRED; pin only after official documentation review |

The implementation must record the selected values, region, billing mode, architecture, and evidence that the limits are supported. No guessed CLI flag or quota is acceptance evidence.

### Container acceptance criteria

- [ ] A clean, secret-free build produces one image manifest whose digest is recorded before deployment.
- [ ] Static inspection proves `amd64`, non-root (or documented exception), no privileged/host-volume/socket/background-daemon behavior, and no secret or dump in any layer.
- [ ] A local contract test proves binding to `0.0.0.0:$PORT`, graceful SIGTERM behavior, finite shutdown, bounded temporary use, and sanitized logs.
- [ ] The selected resource values pass an approved staging load test without unbounded memory, retry, or instance growth.

## 4. Cloud SQL for MySQL contract

### Topology and identity

Staging and production MUST use separate Cloud SQL instances and databases. The target database identity is the selected environment/instance/database tuple; it is not a username. The serving service MUST use a dedicated runtime database user, and the migration Job MUST use a separate dedicated migration database user. They target the same selected environment database identity but are distinct principals with only the privileges required for their paths. Neither user is MySQL root, and no root password is supplied to Cloud Run. No Cloud Run service includes a MySQL container.

Separate staging and production service accounts are required; runtime, migration, and deployment authorities SHOULD be separate. Proposed least-privilege roles are `roles/cloudsql.client` for Cloud SQL connectivity and only the minimum additional roles required for Secret Manager access and Cloud Run execution/deployment; exact role bindings are OPEN until the selected mechanism and organization policy are reviewed. The proposed roles are not authorization to create or bind them. See [Cloud Run service identity](https://cloud.google.com/run/docs/configuring/services/service-identity) and [Cloud SQL IAM](https://cloud.google.com/sql/docs/mysql/iam-authentication).

### Connectivity decision matrix — do not silently select

| Option | Required review | Security/operational trade-off | Status |
|---|---|---|---|
| Cloud SQL public IP plus supported Cloud SQL connector/Auth Proxy path | Confirm connector support for the Java/MySQL client, service-account permission, TLS behavior, egress path, and failure semantics; do not hand-write proxy flags from memory | Simpler network setup; public endpoint exists but access is controlled by the supported connector path; connection limits and connector overhead require testing | OPEN |
| Cloud SQL private IP plus Direct VPC egress | Confirm private-services access, VPC/subnet/egress requirements, region compatibility, supported Cloud Run configuration, and cost | Smaller public exposure; additional VPC setup and possible networking cost/complexity | OPEN |

Use the official [Connect from Cloud Run to Cloud SQL for MySQL](https://cloud.google.com/sql/docs/mysql/connect-run), [Cloud SQL Java Connector](https://docs.cloud.google.com/sql/docs/mysql/connect-connectors), [Cloud SQL Auth Proxy](https://cloud.google.com/sql/docs/mysql/sql-proxy), [private IP](https://cloud.google.com/sql/docs/mysql/private-ip), and [Direct VPC egress](https://cloud.google.com/run/docs/configuring/vpc-direct-vpc) documentation at implementation time. The authoritative staging choice is the Cloud SQL Java Connector over public IP with encrypted transport; the Auth Proxy and private-IP alternatives are retained only as non-authoritative production upgrade paths. The repository currently has no connector dependency, so this remains an implementation/readiness gap and requires a separately authorized dependency/configuration change. The implementation must verify Java compatibility, TLS, IAM, egress, and failure behavior before readiness can pass.

### TLS, pool, and failure contract

- MySQL transport MUST be encrypted using the selected supported mechanism. The current production URL's `useSSL=true` is not, by itself, proof of server identity verification; the final connector/JDBC TLS settings require review.
- Hikari maximum pool size, minimum idle, acquisition timeout, connection timeout, validation/keepalive behavior, and lifetime MUST be explicit and finite. None is explicit in the current repository configuration. No unbounded pool or retry is allowed.
- The invariant is `max_instances × max_pool_per_instance <= approved database connection budget`, with reserved capacity for the migration Job, administrative access, Cloud SQL overhead, and any replicas. The numeric budget is OPEN until the selected Cloud SQL tier's documented connection capacity is verified.
- Startup and transient DB failures MUST fail readiness and produce bounded, sanitized errors. Retries must be finite with backoff; a failing database must not be reported healthy.
- Cloud SQL tier, storage class/size/autogrow, region, HA, maintenance window, backups, PITR, and staging accepted-risk policy are all DECISION REQUIRED. A staging environment may document an accepted backup/HA risk, but it may not imply production safety.

Cloud SQL automated backups and PITR behavior must be selected from [Cloud SQL backup and recovery](https://cloud.google.com/sql/docs/mysql/backup-recovery) and [PITR for MySQL](https://cloud.google.com/sql/docs/mysql/backup-recovery/pitr). Their cost and retention are not zero.

### Cloud SQL acceptance criteria

- [ ] Separate staging/prod instance, database, user, service account, and secret references are recorded without values.
- [ ] One connectivity option is independently approved; its transport encryption and failure test pass.
- [ ] Pool settings and the connection-budget inequality pass for the selected max instances and migration Job.
- [ ] Root login is rejected by application configuration and no root credential is supplied to the service.
- [ ] Backup/PITR/HA/storage decisions and the staging accepted-risk statement are recorded.

## 5. Secret Manager and environment contract

### Classification from current code/configuration

| Name | Classification | Contract |
|---|---|---|
| `MYSQL_DATABASE`, `SPRING_PROFILES_ACTIVE`, `SERVER_PORT`, `APP_BASE_URL`, `WEBSOCKET_ALLOWED_ORIGIN_PATTERNS` | Non-secret configuration | Inject as reviewed values; validate format and environment separation |
| `GOOGLE_CLIENT_ID`, `CLOUDINARY_CLOUD_NAME`, `AGORA_APP_ID` | Identifier/configuration; not a secret by itself | Inject only as needed; never confuse identifier status with authorization |
| `MYSQL_USERNAME`, `MYSQL_PASSWORD` | Database credential | Secret Manager reference; application user only |
| `MYSQL_ROOT_PASSWORD` | VPS/Compose-only root credential | Forbidden in Cloud Run; never create, inject, log, or carry into Cloud Run configuration |
| `GOOGLE_CLIENT_SECRET`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `AGORA_APP_CERTIFICATE` | Secret/credential | Secret Manager reference; never log or bake into an image |

The list is derived from `application.properties`, `application-prod.properties`, `CloudinaryConfig`, Agora configuration, Google OIDC configuration, and `ProductionConfigurationValidator`. The current `springboot4-dotenv` dependency and comments referring to `.env` require a future production verification/change so Cloud Run reads only approved runtime configuration and does not read, copy, or upload `.env`/`.env.local`. This draft does not implement that change.

### Secret lifecycle

- Secret values MUST be absent from Dockerfile instructions, build args, source, scripts, logs, image layers, repository history, and rendered configuration.
- This project contract adopts explicit Secret Manager version references for deterministic rollback; `latest` is not accepted by this project rollback policy. This is a project rollback policy, not a Cloud Run platform requirement. Exact version syntax and rollout behavior must follow [Cloud Run secrets](https://cloud.google.com/run/docs/configuring/services/secrets) and [Secret Manager best practices](https://cloud.google.com/secret-manager/docs/best-practices).
- Rotation requires a compatibility plan: add the new secret version, deploy a compatible revision, verify it, then revoke/disable the old version only after the rollback window. A rollback is unsafe if the target revision needs an unavailable secret version.
- Secret names only may appear in this inventory. Values, project IDs, URLs containing credentials, and version payloads are forbidden.

### Acceptance criteria

- [ ] Secret/privacy scan covers tracked and untracked build context without reading `.env` or `.env.local` contents.
- [ ] Staging and production secret names/versions are distinct and recorded; access is least privilege.
- [ ] A rotation and exact-version rollback rehearsal passes without exposing a value.

## 6. Flyway and schema gate

V1–V4 are immutable. Their repository hashes are recorded in §13. No downgrade is allowed. The current application has `spring.flyway.enabled=true` in the production profile and Spring Boot auto-runs Flyway during application startup; this is an unsafe uncontrolled migration behavior for a serving revision and is a current HIGH blocker. A serving revision must not receive traffic until the future implementation disables serving-instance auto-migration and proves that only the controlled migration Job can mutate the schema.

### Required future design

1. Build one immutable image.
2. Run exactly one controlled Cloud Run Job or approved one-off migration execution against the target environment. Task count/parallelism MUST be `1`; retries, task timeout, and the absolute operator deadline MUST be finite and recorded. See [Cloud Run Jobs](https://cloud.google.com/run/docs/create-jobs).
3. The migration gate MUST use the same database identity and selected Cloud SQL path as the service, apply V1–V4 in order, fail on checksum drift, and exit `0` only after success. Any non-zero exit, timeout, ambiguous execution state, or missing evidence blocks traffic.
4. A fresh pre-gate `flyway validate`/schema compatibility check and a post-gate validation MUST pass. Hibernate `ddl-auto=validate` MUST pass against the resulting schema.
5. The serving service MUST NOT perform uncontrolled migration on startup. The likely future configuration is to disable application-instance migration and enable migration only in the Job-specific execution path, but the exact Spring Boot property/entrypoint design is OPEN and must be implemented and tested without changing V1–V4.
6. Backward compatibility between the last-known-good revision and the migrated schema MUST hold for the complete rollback window. If not, the terminal state is `BLOCKED_ROLLBACK_UNSAFE`; no migration downgrade may be attempted.
7. Migration evidence must identify image digest, Job definition/execution, database target identity, checksums, exit status, timestamps, and actor, without caller secrets or database contents. A caller-supplied “success” claim is not evidence.

### Acceptance criteria

- [ ] V1–V4 hashes match the immutable inventory before and after the gate.
- [ ] One-task migration Job has parallelism 1, finite retry/timeout, absolute deadline, and exit-0-only success.
- [ ] Service startup with migrations disabled cannot mutate the schema; migration Job success is a prerequisite for traffic.
- [ ] Fresh pre/post validation and Hibernate validation pass; rollback compatibility evidence is attached.

## 7. Health, readiness, and smoke separation

Four concerns must remain distinct:

| Gate | Purpose | Public exposure |
|---|---|---|
| Process/liveness | Process is responsive and not deadlocked; no database detail | Sanitized liveness endpoint only |
| Startup | Container has started and can be evaluated without premature restart | Cloud Run startup probe only if supported and approved |
| Dependency readiness | Application, datasource, and Flyway validation are usable; DB/Flyway failure is not healthy | Sanitized readiness endpoint only |
| Public smoke | HTTPS URL, redirects/assets, OAuth/session, authz, DB-backed flows, WebSocket/media/provider integrations | Manual/automated release gate, not a probe |

The current paths are `/actuator/health/liveness` and `/actuator/health/readiness`. Public responses must contain status only or an explicitly approved sanitized shape: no DB host, SQL, Flyway details, secrets, exception class/message, provider payload, stack trace, or PII. A dependency failure must make readiness non-healthy while liveness remains process-oriented. The existing Actuator security tests are baseline evidence.

Probe type support, HTTP status handling, initial delay, period, timeout, failure threshold, and startup budget MUST be selected from the current [Cloud Run health-check documentation](https://cloud.google.com/run/docs/configuring/healthchecks); no invented values are accepted. Proposed staging budgets are: startup 120s, each probe request 5s, and a bounded deployment smoke deadline 10 minutes, all DECISION REQUIRED until platform support and workload measurements are confirmed. Retries must have a cap and deadline.

Acceptance requires a failed DB test to fail readiness, a process-only liveness test to remain healthy, sanitized public responses, no restart storm, and a graceful-shutdown readiness transition.

## 8. Revisions, traffic, and evidence freeze

The release sequence is a future controlled procedure, not an action in this task:

1. Resolve source commit, dependency lock state, Dockerfile/base-image digests, and build metadata.
2. Build once; scan the resulting image; verify and record its immutable digest. The same digest is the only artifact eligible for Job and service.
3. Push the immutable artifact to Artifact Registry only after authorization; deployment must reference the digest, not a mutable tag. See [Artifact Registry image digests](https://cloud.google.com/artifact-registry/docs/docker/pushing-and-pulling).
4. Run the migration gate and freeze evidence.
5. Deploy the candidate revision with `0%` production traffic. A candidate tag may be used only for testing and must never be an OAuth callback origin. Use the official [Cloud Run rollout/rollback/traffic documentation](https://cloud.google.com/run/docs/rollouts-rollbacks-traffic-migration), not guessed flags.
6. Run startup/readiness checks and the public staging smoke suite; record revision name, digest, configuration fingerprint, secret-version references, Job execution, actor, and timestamps.
7. After the evidence freeze, assign traffic only through the approved actor and IAM path. Run the approved post-deploy observation gate below before declaring deployment success.

Resource creation (project, APIs, billing, Artifact Registry, service, Job, SQL, Secret Manager, VPC) and deploy/traffic operations are separately unauthorized in this phase. Proposed IAM must be reviewed against [Cloud Run deployment permissions](https://cloud.google.com/run/docs/reference/iam/roles) and [Cloud Run traffic management](https://cloud.google.com/run/docs/rollouts-rollbacks-traffic-migration). A revision URL is never an OAuth callback.

### 8.0 Input and service URL validation

The offline input interface is one UTF-8 JSON document. Its root is an object;
empty, whitespace-only, truncated, syntactically invalid, trailing-garbage,
duplicate-key, unknown-field, alias, null, wrong-type, malformed, non-finite,
out-of-range, or wrong-shape input is invalid. Duplicate keys are rejected at
every object depth before validation. The accepted top-level fields are exactly
the following (with `overall_deadline_seconds` optional):

| Field | Required | Exact type and constraint | Authority / public status |
|---|---|---|---|
| `target_platform`, `environment`, `region` | yes | string; exact values `GOOGLE_CLOUD_RUN`, `staging`, `asia-southeast1` | reviewed deployment input; not plan output |
| `project`, `service_name`, `candidate_revision`, `previous_revision` | yes | non-empty strings; project and names use the existing lowercase project/revision grammar | deployment evidence; not public |
| `candidate_image`, `previous_image`, `runtime_image` | yes | non-empty immutable image strings matching `@sha256:` plus 64 hexadecimal characters; placeholders forbidden | trusted artifact evidence |
| `service_manifest`, `migration_manifest` | yes | exact object shape `{spec:{template:{containers:[{image:<immutable>}]}}}`; no other key at any nested level | trusted manifest evidence |
| `candidate_revision_evidence`, `previous_revision_evidence` | yes | object with exactly `project`, `region`, `service`, `revision`, all non-empty strings | trusted revision evidence |
| `oauth_callback` | yes | non-empty string; consistency with the canonical trusted deployment URL is checked after deployment | external callback configuration; not a service-URL authority |
| `identities` | yes | object with exactly `runtime`, `migrator`, `deployer`; each non-empty identity string | internal evidence; not public |
| `secret_versions` | yes | object with exactly `runtime` and `migrator`; each value is a one-element non-empty string list | secret reference evidence; values never public |
| `migration_before_serving`, `no_migration_downgrade` | yes | boolean, not integer or string | deployment control evidence |
| `migration_result`, `smoke_result`, `observation_result` | yes | case-sensitive enum: `pass`, `fail`, or `ambiguous` | gate evidence |
| `observation_policy` | yes | exact object with `minimum_samples` (integer), `maximum_gap_seconds` and `freshness_seconds` (finite positive numbers), and exact `thresholds` object `{max_error_rate,max_latency_ms}` | existing observation policy evidence |
| `budgets` | yes | exact integer map: migration 900, startup 120, probe 5, smoke 600, observation 1800, rollback 600 seconds | approved workflow constants |
| `cost_preflight` | yes | exact object with `billing_approval_evidence`, `currency`, `monthly_budget`, `alerts_percent`, `notification_destination`, `expiration`, `region`, `cloud_run`, `cloud_sql`, `networking`, `retention_policy`, `log_policy`, `recurring_inventory`, `teardown_owner`, `teardown_deadline`; nested resource maps are exact | reviewed staging evidence |
| `overall_deadline_seconds` | no | finite integer or float in `[0.001,3125]`; booleans are not numbers | internal guardrail; not public |

No `version`, `schemaVersion`, URL alias, runner, token, capability, deadline,
or live-execution field is supported. Unknown fields are rejected. Strings are
not trimmed for acceptance. Numeric values must be finite; booleans are not
integers. Validation is complete before any runner call, workflow mutation, or
evidence emission. All malformed-input and validation failures use exit `2`
and emit only `code=INVALID_DEPLOYMENT_INPUT`, message
`Deployment input validation failed.`, and one safe reason from the allowlist
`JSON_SYNTAX`, `DUPLICATE_FIELD`, `UNKNOWN_FIELD`, `MISSING_FIELD`,
`INVALID_TYPE`, `INVALID_RANGE`, `INVALID_TIMESTAMP`, `INVALID_SERVICE_URL`.
No payload, field value, URL, credential, parser detail, exception, or
traceback is reflected. Runtime operation failures retain their existing
non-input exit contracts.

The authoritative service URL is supplied only by the trusted deployment runner
for the current invocation; no external input field supplies or overrides it.
`stable_service_url`, `service_url`, `url`, environment overrides, and
caller-supplied CLI URL options are unknown or forbidden fields and are
rejected. The accepted
URL is an absolute URI with lowercase `https`, an ASCII lowercase hostname,
no userinfo, port, query, fragment, backslash, whitespace, control/NUL,
percent-encoding, Unicode/IDNA conversion, trailing dot, IP literal,
localhost, private, loopback, link-local, multicast, reserved, or unspecified
address. The hostname must end at the exact `.run.app` label boundary, have at
least one preceding label, have labels of 1–63 ASCII lowercase letters,
digits, or hyphens that do not begin or end with a hyphen, and be at most 253
characters. The path must be empty or `/`; canonical storage and use omit the
trailing `/`. `https://service.run.app.evil.example`, `https://run.app.evil.example`,
and `https://evilrun.app` are invalid. The URL is parsed once, validated as a
structured value, canonicalized, and then reused without reparsing for smoke
and observation. Validation occurs after the trusted deployment result and
before smoke or observation action; it never logs or reflects the raw URL. If
a trusted deployment operation has already mutated state before returning a
missing or invalid URL, the existing rollback state machine requires its
exactly-once rollback behavior; input exit `2` does not authorize success.

### 8.1 Approved post-deploy observation gate

This observation contract is approved for the Cloud Run Slice 3 post-deploy gate. It does not authorize resource creation, deployment, traffic mutation, live migration, or OAuth-provider changes.

Observation starts only after migration, candidate deployment, and candidate smoke have passed, with no terminal deployment failure and before rollback. It is required before `DEPLOYED` may be recorded. It is not run after migration, deployment, or smoke failure.

Let `t0` be the observation start immediately after smoke-success evidence is validated and consumed. The observation window is exactly `[t0, t0+1800s)`. No sample is collected at `t0`. For each `i = 1..30`, sample `i` covers `[t0+(i-1)60s, t0+i60s)` and is collected at `t0+i60s`; therefore sample 1 is collected at `t0+60s` and sample 30 at `t0+1800s`. There is no sample 0 or 31. At `now >= t0+1800s`, no new sample is dispatched. A telemetry grace period of at most 3 minutes may receive late telemetry only when its event timestamp is inside the observation window; grace does not extend the window or permit sample 31. The hard operation deadline is exactly `t0+1980s`; results at or after it are late and cannot alter the terminal verdict.

Each sample has this exact schema and no extra fields: `sample_index` (integer 1 through 30; booleans are not integers), `window_started_at`, `window_ended_at`, and `observed_at` (trusted server timestamps serialized exactly as `YYYY-MM-DDTHH:mm:ss.SSSZ`, UTC literal `Z`, exactly millisecond precision), `request_count` and `server_error_count` (non-negative integers), `latency_p95_ms` (non-negative integer or `null` only when `request_count == 0`), `probe_attempted` and `probe_succeeded` (booleans), and `observed_revision` (non-empty string). `window_started_at` and `window_ended_at` are the exact half-open sample-interval boundaries. `observed_at` is the trusted server collection time `t0+sample_index*60s`; it may equal `window_ended_at`, including `t0+1800s` for sample 30, and is not itself a telemetry event timestamp. Any telemetry event timestamp used to validate the sample must lie in `[window_started_at,window_ended_at)`. `server_error_count` must not exceed `request_count`; when `request_count == 0`, `server_error_count` must equal 0 and latency is `null`; when `request_count > 0`, latency must be non-null. Sample indexes must be contiguous, unique, and ordered; trusted server timestamps must be monotonic and use the exact format above. Scheduling and timeout decisions always use the trusted monotonic clock, never a client timestamp. A valid sample requires both a completed metric query and one successful synthetic HTTPS health/readiness probe for the candidate revision. Probes must not create business data or perform domain mutation.

Metrics are filtered by project, region, service, candidate revision, invocation, observation attempt, and server-controlled time window. Evidence from another revision, service, region, attempt, invocation, or an ambiguous source is invalid. The candidate revision must be verified by trusted revision evidence rather than an untrusted header or payload. No URL query, credential, cookie, token, or response body is logged.

Observation passes only with 30/30 valid samples and probes, no stale/duplicate/replayed/cross-invocation evidence, no interval gap, and successful one-time consumption of all evidence. The six non-overlapping half-open buckets are B1 `[t0,t0+300s)`, B2 `[t0+300s,t0+600s)`, B3 `[t0+600s,t0+900s)`, B4 `[t0+900s,t0+1200s)`, B5 `[t0+1200s,t0+1500s)`, and B6 `[t0+1500s,t0+1800s)`; each contains five sample intervals (B1 samples 1–5, B2 6–10, B3 11–15, B4 16–20, B5 21–25, B6 26–30). Interval assignment uses the sample interval, not response-arrival time; start is inclusive and end exclusive, so `t0+1800s` is outside the window. The thresholds are: probe success 30/30; aggregate server error rate at most 1.0%; no five-minute bucket above 2.0% server errors; aggregate trusted-telemetry p95 latency at most 1,000 ms; no five-minute bucket p95 above 1,500 ms; and 100% coverage. Aggregate error rate is `sum(server_error_count) / sum(request_count)` with exact rational comparison to 1.0%; when total requests are zero it is 0%. A zero-request bucket has `NOT_APPLICABLE` latency, is not a latency breach, and is not success by itself; coverage, probes, identity, freshness, and all other gates remain mandatory. Aggregate p95 must come from trusted aggregation for the complete window, not an average of sample or bucket p95 values. Equality at every threshold passes; only `>` breaches.

Observation fails immediately on a failed probe, revision mismatch, malformed/stale/replayed/duplicate/cross-invocation evidence, two completed consecutive or three completed total bucket breaches of the same threshold, operation timeout, trusted metric-provider terminal error, revoked ownership, or entry into deployment failure/rollback. A partial bucket is not evaluated for a bucket breach. A single completed bucket breach is retained as a pending breach while observation continues, but it necessarily fails final evaluation. At the end of the window and the three-minute grace period, missing coverage, any bucket breach, aggregate threshold breach, unvalidated/unconsumed samples, or unprovable candidate/invocation ownership is failure. Missing telemetry is never replaced with zero; retries remain within the grace period and hard deadline. Telemetry at exactly a bucket or window end belongs to the next half-open interval, or is excluded at `t0+1800s`.

The exact lifecycle events are `observation-started`, `observation-sample-recorded`, and exactly one of `observation-succeeded`, `observation-failed`, or `operation-timed-out`, followed where applicable by the existing Micro-slice 3 cleanup/forced-cleanup/late-result rules. `observation-started` occurs once before samples; success requires exactly 30 samples; no sample follows a terminal outcome; duplicates and out-of-order events fail closed. Observation requests carry internally generated invocation, operation, attempt, candidate, deadline, owner, and capability associations through the trusted internal envelope. They are defensive-copied, cannot be overwritten by payload, and are not exposed in public plan output. The runner must derive its response from the request it receives.

On observation failure or timeout, ownership is revoked, cancellation is requested once, Micro-slice 3 cleanup is applied, and a valid rollback is requested under the Micro-slice 2 contract when the candidate was deployed. Rollback success does not convert the deployment failure into success. Observation success is the only observation outcome that permits the deployment success transition.

## 9. Rollback contract and terminal states

Rollback means traffic rollback to the exact recorded last-known-good revision and digest. It does not rebuild an image, rerun an uncontrolled migration, or downgrade the database. The target revision must remain compatible with the current schema and exact secret versions. The operation must be idempotent and safe to retry.

After rollback, verify revision identity, readiness, sanitized public health, HTTPS public smoke, OAuth/session behavior, authz, and DB-backed behavior. The post-deploy observation deadline and sampling policy are fixed by Section 8.1; other rollback-operation deadlines remain subject to their separate implementation approvals.

Required terminal states:

- `DEPLOYED`: candidate traffic assigned and observation gate passed.
- `ROLLED_BACK`: traffic returned to the exact last-known-good revision and post-rollback checks passed.
- `FAILED_BEFORE_TRAFFIC`: candidate/build/migration/health gate failed while candidate had no serving traffic.
- `FAILED_AFTER_TRAFFIC_ROLLBACK_CONFIRMED`: candidate had traffic, rollback completed, and post-rollback evidence passed.
- `BLOCKED_ROLLBACK_UNSAFE`: schema, secret version, revision identity, or traffic state makes rollback unsafe or ambiguous.

If traffic assignment or the serving revision is ambiguous, stop and use `BLOCKED_ROLLBACK_UNSAFE`; never guess percentages or revision identity. See [Cloud Run revision management](https://cloud.google.com/run/docs/managing/revisions).

## 10. OAuth callback and stable URL contract

The only eligible callback origin is the actual stable HTTPS Cloud Run service URL after the service exists, for example `https://<service>-<opaque>.run.app`; no project, region, service, or hostname may be guessed or precomputed. The stable URL must be recorded from the deployed service metadata and then registered with Google.

`PUBLIC_BASE_URL` is the future canonical Cloud Run contract name. The current repository uses `APP_BASE_URL` in `application-prod.properties` and Compose; no code/config change is made by this document. A future implementation must deliberately map the current name to the future contract name or perform an approved rename, with no ambiguity. The chosen public URL must be identical across public origin, issuer/forwarded HTTPS handling, OAuth redirect URI, cookie/security policy, WebSocket origin allowlist, and browser links. The callback path is `/login/oauth2/code/google`.

Candidate-tag URLs are test-only and must not be registered as OAuth callbacks. Browser verification must cover Google redirect, callback, session cookie flags, logout, forwarded HTTPS, unauthorized/forbidden behavior, and no provider-secret leakage. DuckDNS is out of scope. A future custom domain requires a separate migration plan for DNS, certificates, callback registration, cookies, and origin allowlists.

## 11. Cost controls and teardown

Before any chargeable resource is created, the user must confirm billing account/project ownership, region, currency, budget recipient, and expiration date. A budget alert is not a hard cap. See [Google Cloud budgets](https://cloud.google.com/billing/docs/how-to/budgets) and [Cloud Run pricing](https://cloud.google.com/run/pricing).

The following are decisions required before creation:

- Cloud Run region, billing model, proposed `min=0`, small max-instance ceiling, CPU, memory, concurrency, timeout, and Job runtime.
- Cloud SQL staging/prod tier, storage/autogrow, backups/PITR retention, HA, maintenance window, and network/egress costs.
- Artifact Registry repository region, retention/cleanup policy, logging retention, Secret Manager version/retention policy, and any VPC connector or Direct VPC egress charges.
- Budget threshold(s), currency, notification recipients, alert expiration, and region scope. Do not use a threshold as a promise of spend prevention.

Exact teardown order, approved for the chosen resources, MUST be documented before creation. Baseline order: stop traffic and disable public access; stop/delete Cloud Run services and Jobs; remove revision tags; retain/export required evidence; delete Artifact Registry images/repository according to retention policy; delete Secret Manager versions/secrets only after retention approval; remove VPC connectivity; delete Cloud SQL databases/instances only after backup/export approval; remove remaining logging/monitoring and billing attachments; then perform a residual-cost check for active resources, retained storage, backups, logs, registry objects, network resources, and pending operations. The exact dependency order is OPEN until the resource inventory exists. No zero-cost result may be claimed.

## 12. Public staging smoke gate — future only

There is no real Cloud Run runtime, URL, database, image, migration execution, or staging deployment in this task. The following is a required future manual/reproducible gate against the actual HTTPS `*.run.app` URL:

- [ ] Public DNS/HTTPS reaches the stable service URL; HTTP redirects as approved; no mixed-content asset or WebSocket failure.
- [ ] Google OAuth redirect/callback uses the stable URL, creates the expected session cookie, preserves Secure/HttpOnly/SameSite behavior, and does not expose codes/tokens.
- [ ] Anonymous/authenticated/admin authorization boundaries pass; Actuator is sanitized and only approved endpoints are public.
- [ ] Readiness reflects DB/Flyway health; Hibernate validation passed; representative DB reads/writes pass against staging Cloud SQL.
- [ ] Migration evidence identifies V1–V4 and exact image/revision digest; no serving instance performed an uncontrolled migration.
- [ ] Cloudinary avatar flow and Agora token/video call flow are tested only if included in the approved staging scope; credentials and provider payloads remain absent from logs.
- [ ] WebSocket/session recovery and browser media permissions are verified over HTTPS. Use two independent browsers/manual confirmation if the release owner requires it; record that decision.
- [ ] Record revision/digest identity, status/error rate, p50/p95 latency, cold-start behavior, instance count, SQL connection usage, and any retry/timeout evidence against approved thresholds.
- [ ] Public exposure, ingress policy, candidate-tag isolation, rollback identity, and residual-cost checks pass.

## 13. Readiness review checklist and inventory template

### Independent readiness checklist

- [ ] Contract review is read-only, independent, and checks every OPEN/BLOCKED decision.
- [ ] Repository-ready items, required code/config changes, resources, IAM, secrets, costs, OAuth, and rollback evidence are complete.
- [ ] Production deployment readiness has not been evaluated or implied.
- [ ] `APPROVED` is not assigned until the independent review passes.
- [ ] User authorization is separately obtained before any implementation or resource creation.

### Readiness inventory (fill during a future review)

| Area | Evidence / decision | Status |
|---|---|---|
| Repository-ready items | Dockerfile/context scan, build reproducibility, tests, architecture, digest policy | OPEN |
| Code/config changes likely needed | `PORT`/bind, Cloud SQL Java path, Hikari bounds, migration Job mode, dotenv exclusion, SQL-log sanitization, health/probe settings, future `PUBLIC_BASE_URL` mapping | OPEN / BLOCKED |
| Planned Cloud resources | Project, APIs, Artifact Registry, staging/prod Cloud Run services and Jobs, Cloud SQL, Secret Manager, VPC/connectivity, budgets | UNAUTHORIZED / OPEN |
| Proposed IAM roles | Separate runtime/migration/deploy identities; least privilege review | OPEN |
| Secret names only | DB credential, Google secret, Cloudinary credentials, Agora certificate; exact names/version policy | OPEN |
| Recurring cost categories | Cloud Run, SQL, storage/backups/PITR, registry, logs, secrets, networking/connectivity, Jobs | OPEN |
| Cost guardrails | Billing confirmation, budget alerts, recipients, expiration, max instances, teardown/residual scan | OPEN |
| Manual user actions | Project/billing approval, OAuth console registration, reviewer approval, deployment authorization | BLOCKED until user acts |
| OAuth changes | Stable run.app URL, redirect URI, issuer/base/origin consistency, browser verification | OPEN |
| Implementation/readiness gaps | `SERVER_PORT` vs Cloud Run `PORT`, serving auto-migration enabled, missing Hikari bounds, SQL logging/sanitization, missing Java Connector dependency, exact probe/resource implementation, migration execution design, production policy | BLOCKED PENDING IMPLEMENTATION |
| Contract defects | Region, staging resource defaults, budget/alerts, Cloud SQL Java connectivity, target database identity, database users, service accounts, IAM baseline, Hikari budget, rollback compatibility selection | NONE after Decision Closure; user confirmation is required before resource creation |
| Implementation slices | Container; IAM/secrets; SQL; migration Job; health; staging deploy; smoke; traffic; rollback; teardown | OPEN |

### Immutable hash inventory observed for this draft

| Artifact | SHA-256 / status |
|---|---|
| Existing VPS spec `docs/specs/spec_deployment_hardening.md` | `7C19DF66599FB475358A1A3CD1F52CBCF641C045A7753E74A7DAFFE5E2E35DC7` |
| Separate deployment Phase 2 spec/artifact | Not present or separately identifiable in the inspected repository; hash `N/A` rather than invented |
| `V1__initial_schema.sql` | `3C34FFAC1CCD41525A52FF55F63A0201BCDF2D481567C5109C531FA2D6746429` |
| `V2__lifecycle_v2_schema.sql` | `C0E67F28107DAECA5B666ACC2DF0803C62CCC356439455A1EDCED2D2A2F03D73` |
| `V3__lifecycle_v2_data_migration.sql` | `04377556DDBC2225B9D0C3CD127FE9D6BB23D1F9E08412CAE5F73ADF9BE24937` |
| `V4__admin_rubric_schema.sql` | `986366E2021B6F4D716A6C11F7EFEFBB9616BE5C0AE3532F5940427B274A607E` |
| This Cloud Run contract | Compute after final write; it is DRAFT and not authority for implementation |

### Contract review readiness report

This report is part of the contract and reflects the read-only repository audit for this revision:

| Finding | Repository evidence | Contract disposition |
|---|---|---|
| Cloud Run port mismatch | `application-prod.properties` uses `server.port=${SERVER_PORT}`; Cloud Run supplies `PORT` | IMPLEMENTATION/READINESS GAP; future implementation must map and test `0.0.0.0:$PORT` |
| Serving auto-migration | `spring.flyway.enabled=true` in `application-prod.properties` | IMPLEMENTATION/READINESS GAP; serving auto-migration must be disabled and isolated to the one-task migration Job |
| Hikari controls | No explicit Hikari pool/timeouts/lifetime settings found in inspected properties | IMPLEMENTATION/READINESS GAP; finite settings and the closed pool-budget formula are required |
| SQL logging/privacy | `logging.level.org.hibernate.SQL=INFO`; sanitization is not proven by the inspected repository | IMPLEMENTATION/READINESS GAP; serving SQL/bind logging must be disabled and sanitization tested |
| Root credential pattern | `compose.yaml` contains `MYSQL_ROOT_PASSWORD` for the VPS-only MySQL container | IMPLEMENTATION/READINESS GAP; forbidden in Cloud Run and excluded from Cloud Run inputs |
| Database identity/principals | Current datasource uses Compose host `mysql` and `MYSQL_USERNAME`; Cloud Run target is now defined in the closure above | CONTRACT CLOSED; implementation must use the distinct target, runtime user, migration user, and IAM identities specified above |
| Cloud SQL Java path | Repository has no connector dependency yet | CONTRACT CLOSED; authoritative staging path is Cloud SQL Java Connector over public IP; implementation remains pending |
| URL naming | Current repo uses `APP_BASE_URL`; future Cloud Run contract name is `PUBLIC_BASE_URL` | IMPLEMENTATION/READINESS GAP with compatibility rule; no code change in this task |
| Phase 2 integrity | No uniquely attributable separate deployment Phase 2 artifact was found; a hash is not inventable | Limitation recorded as `N/A`; do not edit Phase 2 files |

No GCP action was authorized or performed: no project, API, billing, Artifact Registry, Cloud Run service/Job, Cloud SQL instance, Secret Manager secret/version, VPC, deployment, migration, traffic assignment, or staging resource was created or changed. Readiness remains blocked until the Decision Matrix is resolved and the required implementation work is separately approved and verified.

### Verdict

`DRAFT READY FOR INDEPENDENT CONTRACT CLARIFICATION REVIEW`

This verdict means the contract now separates contract defects from implementation/readiness gaps and has one authoritative staging target. It does not mean Cloud Run readiness or implementation is approved. The document status remains `DRAFT / CLOUD RUN READINESS NOT ASSESSED`; repository readiness remains `BLOCKED PENDING IMPLEMENTATION`, and resource creation remains unauthorized until user confirmation and separate authorization.

### Required handoff

The next step is an independent read-only contract review. The reviewer must either return a deterministic `APPROVED` with the remaining decisions resolved, or keep this document `DRAFT`/`BLOCKED` with evidence. Only after that review may the user separately authorize implementation or resource creation. Production staging/deployment readiness is not evaluated by this document.
