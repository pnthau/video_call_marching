# Cloud Run Slice 2 repository artifacts

These files are non-executable templates and review inputs. They do not create
projects, enable APIs, create billing, apply IAM, create secrets, run jobs, or
assign traffic. Any application requires a later, separately authorized gate.

Authoritative staging target:

- region: `asia-southeast1`
- service: `video-call-staging`
- Cloud SQL instance/database: `video-call-staging-sql` / `video_call_staging`
- runtime user: `app_runtime`
- migration user: `app_migrator`
- service account separation: runtime, migrator, deployer
- runtime limits: 1 vCPU, 1 GiB, concurrency 20, timeout 300s, min 0, max 3
- serving pool: maximum 5, minimum idle 1, ceiling `3 * 5 = 15`

The image reference is a digest placeholder and must be replaced only by a
reviewed immutable digest at execution time. `ACTUAL_SERVICE_URL` is not a
hostname claim; the stable `run.app` URL must be obtained after service
creation. No custom domain or DuckDNS is part of this slice.

Before an apply-authorized operation, only non-secret `${...}` placeholders
are rendered from a reviewed deployment input. The rendered file is checked
for unresolved placeholders and is not committed. `PUBLIC_BASE_URL` is filled
from the actual stable service URL after service creation; a guessed `run.app`
hostname is never authoritative.

Migration execution is one task with parallelism one, bounded retry and task
timeout. It activates only `migration`; serving uses `cloudrun`. Serving
instances have Flyway disabled. A successful migration requires Flyway
validation/migration and Hibernate validation; non-zero, timeout, or ambiguous
job state blocks traffic. Rollback never downgrades the schema.

The public-IP Cloud SQL Java Connector path is represented by the existing
application profile. These artifacts do not claim a live Cloud SQL connection.
Secret references are names and pinned versions only; no secret value belongs
in this directory. `MYSQL_ROOT_PASSWORD` is forbidden for Cloud Run.

Before resource creation, the operator must separately confirm billing,
monthly budget/alerts, expiration date, exact supported MySQL minor/edition,
backup/PITR policy, IAM bindings, and organization policy.
