# Rollback and deployment safety runbook

Rollback is an app-image operation only. It requires `TARGET_ENV=isolated-staging`, explicit operator approval, and the previous immutable image digest. Database rollback is forbidden by default; use a backward-compatible app, reviewed forward-fix migration, or an explicitly approved restore procedure.

Before deployment, run preflight checks, verify the external backup checksum, confirm V1–V4 immutability, and complete readiness plus HTTPS/WebSocket smoke. On failure, remove traffic, record sanitized failure metadata, switch to the previous image digest, wait for readiness, and rerun smoke. Do not publish MySQL port 3306 or the app port.

Cleanup may remove only explicitly created isolated test resources. Retain backup evidence according to operator policy; never use destructive volume cleanup as part of rollback.
