#!/usr/bin/env bash
set -Eeuo pipefail
export COMPOSE_DISABLE_ENV_FILE=1
umask 077
usage() { echo "usage: $0 --previous-image IMAGE@sha256:DIGEST --evidence-dir ABSOLUTE_DIR --project-name NAME" >&2; exit 2; }
fail() { echo "$1" >&2; exit 71; }
[[ ${TARGET_ENV-} == isolated-staging && ${ROLLBACK_APPROVED-} == YES ]] || fail "rollback approval/target invalid"; [[ ${DB_ROLLBACK-} != YES ]] || fail "database rollback is forbidden"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd); REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$SCRIPT_DIR/..")" && pwd)
PROJECT_NAME=${PROJECT_NAME-}; PREVIOUS_IMAGE=; EVIDENCE_DIR=
while (($#)); do case "$1" in --previous-image) PREVIOUS_IMAGE=${2-}; shift 2;; --evidence-dir) EVIDENCE_DIR=${2-}; shift 2;; --project-name) PROJECT_NAME=${2-}; shift 2;; *) usage;; esac; done
[[ $PROJECT_NAME =~ ^isolated-staging-[A-Za-z0-9._-]+$ && $PROJECT_NAME != *production* && $PROJECT_NAME != *development* ]] || fail "isolated staging project is required"
# Canonical compose policy: callers cannot override this repository-local path.
COMPOSE_FILE="$REPO_ROOT/compose.yaml"; [[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" ]] || fail "canonical repository compose.yaml is missing"
[[ $PREVIOUS_IMAGE =~ ^[^[:space:]@]+@sha256:[0-9A-Fa-f]{64}$ && $PREVIOUS_IMAGE != *:latest@* ]] || fail "previous immutable image is required"; [[ $EVIDENCE_DIR = /* && $EVIDENCE_DIR != "$PWD"/* ]] || fail "rollback inputs are invalid"
command -v docker >/dev/null || fail "docker is required"; command -v timeout >/dev/null || fail "timeout is required"; command -v flock >/dev/null || fail "flock is required"; exec {LOCK_FD}>"${DEPLOY_LOCK_FILE-/tmp/videocall-marching-isolated-staging.lock}"; flock -n "$LOCK_FD" || fail "another operation is running"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE"); docker image inspect "$PREVIOUS_IMAGE" >/dev/null 2>&1 || fail "previous exact image is not available locally"; export APP_IMAGE="$PREVIOUS_IMAGE"; "${COMPOSE[@]}" config --quiet >/dev/null || fail "rollback compose configuration failed"; mkdir -p -- "$EVIDENCE_DIR"
STATE=ROLLBACK_RUNNING; trap "echo DEPLOYMENT FAILED ROLLBACK FAILED >&2; exit 71" INT TERM HUP
if timeout --signal=TERM --kill-after=2s 120s "${COMPOSE[@]}" stop app >/dev/null && timeout --signal=TERM --kill-after=2s 120s "${COMPOSE[@]}" rm -f app >/dev/null && timeout --signal=TERM --kill-after=2s 120s "${COMPOSE[@]}" up -d app >/dev/null && "$SCRIPT_DIR/health-gates.sh" --project-name "$PROJECT_NAME" --service app --port "$SERVER_PORT" --attempts 12 --interval 10 --budget 120; then
  STATE=ROLLBACK_PASS; printf "rollback=passed database_rollback=forbidden\n" >"$EVIDENCE_DIR/rollback-evidence.txt"; printf "DEPLOYMENT FAILED ROLLBACK PASS\n"; exit 0
fi
STATE=ROLLBACK_FAILED; echo "DEPLOYMENT FAILED ROLLBACK FAILED" >&2; exit 71
