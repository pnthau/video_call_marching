# Cloud Run Slice 3 offline runbook

This release gate is offline-only. It validates release evidence and emits a
plan or dry-run result; it never calls GCP, Docker, Cloud SQL, Secret Manager,
IAM, billing, a migration client, a network endpoint, or an arbitrary
subprocess. Public CLI options are limited to `--input`, `--validate`,
`--plan`, and `--dry-run`. Authorization tokens, live mode, runner paths,
executables, markers, capability values, and state/mutation options are
unknown public inputs and fail closed.

The trusted harness imports the orchestrator's internal structured API. Its
single protocol source of truth is the internal `_get_protocol_spec()` handle;
the marker and version are not part of public output. A fresh in-memory FakeRunner and an opaque
capability object are created for each run. The capability is associated with
the runner by object identity and each operation is one-shot. Missing,
serialized/arbitrary-string, stale, wrong-runner, wrong-marker, or replayed
capabilities fail. The capability is never serialized, logged, or exposed by
the public plan output. Public plan mode only validates and reports a
non-mutating plan; it grants no execution authority.

The input must contain exact service and migration manifest image fields and a
runtime image, all equal to the same immutable `@sha256:<64 hex>` digest. It
must contain normal Cloud Run revision names with exact project/region/service
ownership. The canonical HTTPS `*.run.app` service URL is not caller input;
it is authoritative only when returned by the trusted deployment runner after deployment and is
validated before smoke or observation; the existing
bounded deployment guardrails. Localhost, IP literals, ports, queries,
fragments, and caller URL overrides are rejected.

```text
python3 slice3_orchestrator.py --input release.json --validate
python3 slice3_orchestrator.py --input release.json --plan
python3 slice3_orchestrator.py --input release.json --dry-run
```

The offline harness provisions two structured simulated operations through the
internal API and reports expected/actual exit status with dynamic accounting:
`passed + failed + skipped = total`, with zero failures. It also checks public
argument rejection, no gcloud/subprocess invocation, source preservation,
no new bytecode, and that an intentionally reversed assertion in a temporary
harness copy returns nonzero. The Slice 2 validator and deployment manifests
remain outside this micro-slice.

Rollback evidence is verified through one trusted offline operation after a
current-invocation smoke failure. The target is the distinct
last-known-good revision. Each rollback context creates internal, non-public
`invocation_id` and `rollback_attempt_id` values; the runner must echo both,
and each result is consumed at most once. The exact result schema and evidence
sequence are validated before rollback success is recorded. Malformed,
mismatched, stale, replayed, or failed rollback results are reported as
failure; duplicate or extra evidence is rejected. Rollback success does not
turn the original deployment failure into success. Migration failure and
successful smoke emit `rollback-not-attempted` evidence. This FakeRunner flow
is simulation only; there is no live runner or GCP mutation.

Deadline and cleanup evidence is invocation- and attempt-scoped. Migration,
smoke, and rollback use bounded monotonic deadlines; a timeout revokes the
current owner, requests cancellation once, performs bounded cleanup, and may
request forced cleanup once when the trusted runner supports it. Completion at
the deadline is treated deterministically as a timeout. Late results are
rejected and cannot change the terminal verdict or affect a newer attempt.
Cleanup is idempotent, stale acknowledgements are rejected, and unresolved
cleanup is reported for operator escalation. The local Python child used by
the offline harness is inert and temporary; its lifecycle is owned by the
harness and is not a production subprocess runner.

Post-deploy observation begins only after migration, candidate deployment, and
smoke have all succeeded and their evidence has been consumed. It runs for the
half-open window `[t0, t0+1800s)`, with no sample at `t0`: samples 1 through 30
cover consecutive 60-second intervals and are collected at `t0+60s` through
`t0+1800s`. A three-minute telemetry grace period accepts only late evidence
whose event timestamp is inside the observation window; it creates no new
sample. The hard deadline is `t0+1980s`, and `now >= deadline` is a timeout.

Each sample uses the exact ten-field schema defined by the deployment
specification, UTC timestamps in `YYYY-MM-DDTHH:mm:ss.SSSZ` format, and the
candidate revision identity. Samples are grouped into six non-overlapping
five-sample buckets. Coverage and successful synthetic health probes must both
be 30/30. Aggregate error rate must be at most 1.0%, each bucket at most 2.0%,
aggregate trusted p95 latency at most 1,000 ms, and each bucket at most 1,500
ms; equality passes. A zero-request sample has zero errors and null p95, which
is not a latency breach and does not by itself establish success. One bucket
breach is retained for final failure; two consecutive or three total breaches
of the same metric fail early.

Observation requests and evidence are internal, defensive-copied, invocation-
and-attempt-associated records handled by the trusted deadline-aware runner.
Association, owner, capability, deadline, and telemetry payload details are
not public CLI or plan data. Evidence is validated and consumed once, with one
terminal observation outcome. Missing, malformed, stale, replayed, duplicate,
cross-invocation, or late evidence fails closed. Observation failure or
timeout requests the approved rollback when the deployed candidate and
last-known-good target are valid; rollback success does not change the overall
deployment failure. There is no live observation runner, endpoint call, or GCP
mutation in this offline orchestrator.
