#!/usr/bin/env bash
set -Eeuo pipefail
export COMPOSE_DISABLE_ENV_FILE=1
umask 077
usage() { echo "usage: $0 --candidate-commit COMMIT --candidate-image IMAGE@sha256:DIGEST --previous-image IMAGE@sha256:DIGEST --backup-evidence ABSOLUTE_FILE --project-name NAME" >&2; exit 2; }
blocked() { echo "BLOCKED BEFORE MUTATION: $1" >&2; exit 3; }
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd); REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$SCRIPT_DIR/..")" && pwd)
CANDIDATE_COMMIT= CANDIDATE_IMAGE= PREVIOUS_IMAGE= BACKUP_EVIDENCE= PROJECT_NAME=${PROJECT_NAME-}
while (($#)); do
  case "$1" in
    --candidate-commit) CANDIDATE_COMMIT=${2-}; shift 2;; --candidate-image) CANDIDATE_IMAGE=${2-}; shift 2;; --previous-image) PREVIOUS_IMAGE=${2-}; shift 2;; --backup-evidence) BACKUP_EVIDENCE=${2-}; shift 2;; --project-name) PROJECT_NAME=${2-}; shift 2;; *) usage;;
  esac
done
[[ ${TARGET_ENV-} == isolated-staging ]] || blocked "TARGET_ENV=isolated-staging is required"
[[ $PROJECT_NAME =~ ^isolated-staging-[A-Za-z0-9._-]+$ && $PROJECT_NAME != *development* && $PROJECT_NAME != *production* ]] || blocked "isolated-staging project is required"
[[ $CANDIDATE_COMMIT =~ ^[0-9a-fA-F]{40}$ ]] || blocked "candidate commit is not a full commit"
[[ $CANDIDATE_IMAGE =~ ^[^[:space:]@]+@sha256:[0-9a-fA-F]{64}$ ]] || blocked "candidate image is not an immutable digest"
[[ $PREVIOUS_IMAGE =~ ^[^[:space:]@]+@sha256:[0-9a-fA-F]{64}$ && $PREVIOUS_IMAGE != *:latest@* ]] || blocked "previous image is not an approved immutable digest"
[[ -s $BACKUP_EVIDENCE && $BACKUP_EVIDENCE = /* ]] || blocked "backup evidence is missing"
grep -Fxq backup_integrity=verified "$BACKUP_EVIDENCE" || blocked "backup evidence is not verified"
COMPOSE_FILE="$REPO_ROOT/compose.yaml"; [[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" ]] || blocked "canonical repository compose.yaml is missing"
command -v docker >/dev/null || blocked "docker is required"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE"); export APP_IMAGE="$CANDIDATE_IMAGE"
"${COMPOSE[@]}" config --quiet >/dev/null 2>&1 || blocked "compose configuration does not render"
docker image inspect "$PREVIOUS_IMAGE" >/dev/null 2>&1 || blocked "trusted previous image is not inspectable"
for required in "$SCRIPT_DIR/verify-migrations.sh" "$SCRIPT_DIR/health-gates.sh" "$SCRIPT_DIR/predeploy.sh" "$SCRIPT_DIR/rollback.sh" "$REPO_ROOT/Dockerfile" "$COMPOSE_FILE"; do
  [[ -s $required ]] || blocked "required deployment artifact is missing"
done
grep -Eq '^  app:' "$COMPOSE_FILE" || blocked "compose app service is missing"
grep -Eq '^  mysql:' "$COMPOSE_FILE" || blocked "compose database service is missing"
grep -Eq 'mysql-data:' "$COMPOSE_FILE" || blocked "database volume is missing"
grep -Eq 'networks:' "$COMPOSE_FILE" || blocked "deployment network is missing"
grep -Eq 'condition: service_healthy' "$COMPOSE_FILE" || blocked "database health ordering is missing"
"${COMPOSE[@]}" config --services | grep -Fxq mysql || blocked "database prerequisite does not resolve"
"${COMPOSE[@]}" config --volumes | grep -Fxq mysql-data || blocked "database volume prerequisite does not resolve"
[[ ${MYSQL_DATABASE-} != "" && ${MYSQL_USERNAME-} != "" && ${MYSQL_PASSWORD-} != "" && ${MYSQL_ROOT_PASSWORD-} != "" ]] || blocked "required database boundary values are missing"
[[ ${SERVER_PORT-} =~ ^[0-9]+$ && $SERVER_PORT -ge 1 && $SERVER_PORT -le 65535 ]] || blocked "SERVER_PORT is invalid"
[[ ${APP_BASE_URL-} == https://* && $APP_BASE_URL != *localhost* && $APP_BASE_URL != *127.0.0.1* ]] || blocked "APP_BASE_URL is not canonical HTTPS"
[[ ${SPRING_PROFILES_ACTIVE-} == prod ]] || blocked "production profile is not selected"
git -C "$REPO_ROOT" diff --check >/dev/null || blocked "candidate contains whitespace errors"
printf 'predeployment_checks=passed candidate_identity=verified previous_image=inspectable compose=rendered prerequisites=resolved runtime_health=not_checked\n'
