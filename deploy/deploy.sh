#!/usr/bin/env bash
set -Eeuo pipefail
export COMPOSE_DISABLE_ENV_FILE=1
umask 077
usage() { echo "usage: $0 --candidate-commit COMMIT --previous-image IMAGE@sha256:DIGEST --evidence-dir ABSOLUTE_DIR --backup-evidence ABSOLUTE_FILE [--project-name NAME]" >&2; exit 2; }
blocked() { echo "BLOCKED BEFORE MUTATION: $1" >&2; exit 3; }
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd); REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$SCRIPT_DIR/.." )" && pwd)
TARGET_ENV=${TARGET_ENV-}; PROJECT_NAME=${PROJECT_NAME-}; CANDIDATE_COMMIT=; PREVIOUS_IMAGE=; EVIDENCE_DIR=; BACKUP_EVIDENCE=
while (($#)); do case "$1" in --candidate-commit) CANDIDATE_COMMIT=${2-}; shift 2;; --previous-image) PREVIOUS_IMAGE=${2-}; shift 2;; --evidence-dir) EVIDENCE_DIR=${2-}; shift 2;; --project-name) PROJECT_NAME=${2-}; shift 2;; --backup-evidence) BACKUP_EVIDENCE=${2-}; shift 2;; *) usage;; esac; done
[[ $TARGET_ENV == isolated-staging ]] || blocked "TARGET_ENV=isolated-staging is required"
[[ $PROJECT_NAME =~ ^isolated-staging-[A-Za-z0-9._-]+$ && $PROJECT_NAME != *development* && $PROJECT_NAME != *production* ]] || blocked "isolated-staging project name is required"
[[ $CANDIDATE_COMMIT =~ ^[0-9a-fA-F]{40}$ ]] || blocked "full candidate commit is required"
[[ $PREVIOUS_IMAGE =~ ^[^[:space:]@]+@sha256:[0-9a-fA-F]{64}$ && $PREVIOUS_IMAGE != *:latest@* ]] || blocked "approved previous image digest is required"
[[ $EVIDENCE_DIR = /* && $EVIDENCE_DIR != "$PWD"/* && -s $BACKUP_EVIDENCE ]] || blocked "evidence directory or backup evidence is invalid"
command -v docker >/dev/null || blocked "docker is required"; command -v timeout >/dev/null || blocked "timeout is required"; command -v flock >/dev/null || blocked "flock is required"
COMPOSE_FILE="$REPO_ROOT/compose.yaml"; [[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" ]] || blocked "canonical repository compose.yaml is missing"
exec {LOCK_FD}>"${DEPLOY_LOCK_FILE-/tmp/videocall-marching-isolated-staging.lock}"; flock -n "$LOCK_FD" || blocked "another deployment is running"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE"); export APP_IMAGE=videocall-marching:candidate-"$CANDIDATE_COMMIT"
CURRENT_COMMIT=$(git -C "$REPO_ROOT" rev-parse HEAD) || blocked "candidate commit cannot be resolved"; [[ $CURRENT_COMMIT == $CANDIDATE_COMMIT ]] || blocked "candidate commit does not match HEAD"
git -C "$REPO_ROOT" diff --check >/dev/null || blocked "candidate contains whitespace errors"
timeout --signal=TERM --kill-after=5s 120s docker build --pull=false --tag "$APP_IMAGE" --file "$REPO_ROOT/Dockerfile" "$REPO_ROOT" >/dev/null || blocked "candidate image build failed"
CANDIDATE_IMAGE_ID=$(docker image inspect --format "{{.Id}}" "$APP_IMAGE") || blocked "candidate image identity is unavailable"; [[ $CANDIDATE_IMAGE_ID == sha256:* ]] || blocked "candidate image identity is not immutable"; export APP_IMAGE="$APP_IMAGE@$CANDIDATE_IMAGE_ID"
"$SCRIPT_DIR/predeploy.sh" --candidate-commit "$CANDIDATE_COMMIT" --candidate-image "$APP_IMAGE" --previous-image "$PREVIOUS_IMAGE" --backup-evidence "$BACKUP_EVIDENCE" --project-name "$PROJECT_NAME" >/dev/null || exit $?
MIGRATION_RESULT=$(mktemp); trap "rm -f -- \"$MIGRATION_RESULT\"" EXIT; MIGRATION_PASSWORD_ENV=${MIGRATION_PASSWORD_ENV-MYSQL_PASSWORD}; [[ $MIGRATION_PASSWORD_ENV =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || blocked "migration password variable name is invalid"
timeout --signal=TERM --kill-after=2s 60s "$SCRIPT_DIR/verify-migrations.sh" --target-id "isolated-test-$CANDIDATE_COMMIT" --host "${MIGRATION_DB_HOST-127.0.0.1}" --port "${MIGRATION_DB_PORT-3306}" --database "$MYSQL_DATABASE" --user "$MYSQL_USERNAME" --password-env "$MIGRATION_PASSWORD_ENV" >"$MIGRATION_RESULT" || blocked "trusted migration verification failed before mutation"
grep -Fxq "migration_history=V1-V4 checksum_validation=passed file_hashes=verified hibernate_ddl=validate" "$MIGRATION_RESULT" || blocked "trusted migration result is malformed"
"${COMPOSE[@]}" up -d mysql >/dev/null || blocked "database startup failed before mutation"; MYSQL_CONTAINER=$("${COMPOSE[@]}" ps -q mysql); [[ -n $MYSQL_CONTAINER ]] || blocked "database prerequisite is missing"; MYSQL_HEALTH=$(docker inspect --format "{{.State.Health.Status}}" "$MYSQL_CONTAINER" 2>/dev/null || true); [[ $MYSQL_HEALTH == healthy ]] || blocked "database health failed before mutation"
STATE=PRE_MUTATION; ROLLBACK_ATTEMPTED=0; SUCCESS=0; CLEANED=0
cleanup() { local rc=$?; (( CLEANED == 0 )) && CLEANED=1; if (( SUCCESS == 0 && rc == 0 )); then rc=70; fi; return "$rc"; }
rollback_once() { (( ROLLBACK_ATTEMPTED == 0 )) || { echo "DEPLOYMENT FAILED ROLLBACK FAILED" >&2; return 71; }; ROLLBACK_ATTEMPTED=1; STATE=ROLLBACK_RUNNING; trap - INT TERM HUP; if ROLLBACK_APPROVED=YES TARGET_ENV=isolated-staging "$SCRIPT_DIR/rollback.sh" --previous-image "$PREVIOUS_IMAGE" --evidence-dir "$EVIDENCE_DIR" --project-name "$PROJECT_NAME"; then STATE=ROLLBACK_PASS; return 0; fi; STATE=ROLLBACK_FAILED; return 71; }
on_signal() { if [[ $STATE == PRE_MUTATION ]]; then echo "BLOCKED BEFORE MUTATION: signal" >&2; exit 128; elif [[ $STATE == ROLLBACK_RUNNING || $ROLLBACK_ATTEMPTED -eq 1 ]]; then echo "DEPLOYMENT FAILED ROLLBACK FAILED" >&2; exit 71; else rollback_once || exit 71; exit 130; fi; }
trap cleanup EXIT; trap "on_signal" INT TERM HUP; STATE=MUTATED
timeout --signal=TERM --kill-after=2s 120s "${COMPOSE[@]}" stop app >/dev/null && timeout --signal=TERM --kill-after=2s 120s "${COMPOSE[@]}" rm -f app >/dev/null && timeout --signal=TERM --kill-after=2s 120s "${COMPOSE[@]}" up -d app >/dev/null || { rollback_once || exit 71; }
# health-gates.sh owns the canonical compose path; do not pass a caller path.
"$SCRIPT_DIR/health-gates.sh" --project-name "$PROJECT_NAME" --service app --port "$SERVER_PORT" --attempts 12 --interval 5 --budget 60 || { rollback_once || exit 71; }
"$SCRIPT_DIR/health-gates.sh" --https-smoke "$APP_BASE_URL" --budget 60 || { rollback_once || exit 71; }
echo "BLOCKED: browser smoke evidence pending; live HTTPS checks do not fake browser PASS" >&2; rollback_once || exit 71
STATE=DEPLOYMENT_PASS; SUCCESS=1; printf "deployment=passed\n"; exit 0
