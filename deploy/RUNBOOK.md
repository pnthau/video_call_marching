# Slice 3 operational runbook

All commands require an explicitly identified `isolated-staging` target. Never read or upload `.env` files. Supply secret values through the approved boundary; pass only the variable name to scripts via `--password-env`.

1. Pin the reviewed commit and image digest; confirm `git diff --check`, clean migration hashes V1–V4, and a verified pre-migration backup.
2. Render `nginx/nginx.conf.template` with operator-supplied `APP_BASE_URL_HOST`, `SERVER_PORT`, `TLS_CERTIFICATE_PATH`, and `TLS_PRIVATE_KEY_PATH`; certificate material stays outside this repository. The proxy must be the only public boundary, redirect HTTP to HTTPS, and pass WebSocket Upgrade/Connection headers.
3. Run `verify-migrations.sh` against a separate empty isolated test database, then start the app with `SPRING_PROFILES_ACTIVE=prod`; wait for readiness, not liveness alone. `application-prod.properties` uses Flyway and Hibernate `ddl-auto=validate`.
4. Run `health-gates.sh https://<operator-supplied-host>` and perform browser/WebSocket smoke through that HTTPS boundary. Confirm sanitized health, Secure/HttpOnly/SameSite cookies, OAuth callback, origin allowlist, and upload limit.
5. Send SIGTERM to the app container and verify readiness drains before the 30-second graceful-shutdown deadline; record only sanitized status metadata.
6. Run `backup-mysql.sh` to external restricted storage, verify its checksum, restore with `restore-mysql.sh` into a separate disposable isolated test target, and rerun migration/schema/application-read checks.
7. Run `predeploy.sh` only after all evidence exists. If smoke fails, remove traffic and follow `rollback.md`; never automatically downgrade the database.

Operational staging execution is separate from this static artifact contract and remains blocked until the approved target is supplied.
