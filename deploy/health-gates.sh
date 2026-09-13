#!/usr/bin/env bash
set -Eeuo pipefail
export COMPOSE_DISABLE_ENV_FILE=1
usage() { echo "usage: $0 --project-name NAME --service app --port PORT [--attempts 12] [--interval 5] [--budget 60] | $0 --https-smoke https://HOST [--budget 60]" >&2; exit 2; }
fail() { echo "$1" >&2; exit 4; }
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd); REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$SCRIPT_DIR/..")" && pwd)
# Canonical compose policy: callers cannot override this repository-local path.
COMPOSE_FILE="$REPO_ROOT/compose.yaml"; PROJECT_NAME= SERVICE= PORT= ATTEMPTS=12 INTERVAL=5 BUDGET=60 HTTPS_URL=
while (($#)); do
  case "$1" in
    --project-name|--service|--port|--attempts|--interval|--budget|--https-smoke)
      (($# >= 2)) || usage
      case "$1" in
        --project-name) PROJECT_NAME=$2;; --service) SERVICE=$2;; --port) PORT=$2;; --attempts) ATTEMPTS=$2;; --interval) INTERVAL=$2;; --budget) BUDGET=$2;; --https-smoke) HTTPS_URL=$2;;
      esac; shift 2;; *) usage;;
  esac
done
[[ $BUDGET =~ ^[0-9]+$ && $BUDGET -gt 0 ]] || { echo "budget is invalid" >&2; exit 3; }
[[ $ATTEMPTS =~ ^[0-9]+$ && $ATTEMPTS -ge 1 && $ATTEMPTS -le 12 && $INTERVAL =~ ^[0-9]+$ && $INTERVAL -ge 1 && $INTERVAL -le 10 ]] || { echo "health parameters are invalid" >&2; exit 3; }
(( ATTEMPTS * INTERVAL <= BUDGET )) || { echo "health attempts cannot fit the absolute budget" >&2; exit 3; }
deadline=$((SECONDS + BUDGET)); remaining() { local n=$((deadline - SECONDS)); ((n > 0)) && echo "$n" || echo 0; }
if [[ -n $HTTPS_URL ]]; then
  [[ $HTTPS_URL =~ ^https://[^/[:space:]]+/?$ && $HTTPS_URL != *localhost* && $HTTPS_URL != *127.0.0.1* ]] || { echo "explicit HTTPS origin is required" >&2; exit 3; }
  (( BUDGET >= 10 )) || { echo "HTTPS budget is too small" >&2; exit 3; }
  OUT=$(mktemp); HEADERS=$(mktemp); cleanup() { rm -f -- "$OUT" "$HEADERS"; }; trap cleanup EXIT
  curl --fail --silent --show-error --proto '=https' --tlsv1.2 --connect-timeout 2 --max-time "$(remaining)" -D "$HEADERS" -o "$OUT" "$HTTPS_URL/actuator/health/liveness" || fail "HTTPS liveness gate failed"
  grep -Eq 'HTTP/[0-9.]+ 200' "$HEADERS" || fail "HTTPS liveness status failed"; grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$OUT" || fail "HTTPS liveness body failed"
  (( $(remaining) > 0 )) || fail "HTTPS smoke deadline expired"
  curl --fail --silent --show-error --proto '=https' --tlsv1.2 --connect-timeout 2 --max-time "$(remaining)" -D "$HEADERS" -o "$OUT" "$HTTPS_URL/actuator/health/readiness" || fail "HTTPS readiness gate failed"
  grep -Eq 'HTTP/[0-9.]+ 200' "$HEADERS" || fail "HTTPS readiness status failed"; grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$OUT" || fail "HTTPS readiness body failed"
  printf 'https_smoke=live_tls_verified health_routes=sanitized browser_interface=live_evidence_pending\n'; exit 0
fi
[[ -f "$COMPOSE_FILE" && ! -L "$COMPOSE_FILE" && $PROJECT_NAME =~ ^isolated-staging-[A-Za-z0-9._-]+$ && $SERVICE == app && $PORT =~ ^[0-9]+$ && $PORT -ge 1 && $PORT -le 65535 ]] || { echo "isolated service health parameters are invalid" >&2; exit 3; }
COMPOSE=(docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE")
for ((attempt=1; attempt<=ATTEMPTS; attempt++)); do
  (( $(remaining) > 0 )) || fail "health gate timed out"; REQUEST_TIMEOUT=$(( $(remaining) < 2 ? $(remaining) : 2 )); (( REQUEST_TIMEOUT > 0 )) || fail "health gate timed out"
  if timeout --signal=TERM --kill-after=1s "${REQUEST_TIMEOUT}s" "${COMPOSE[@]}" exec -T "$SERVICE" curl --fail --silent --show-error --connect-timeout 2 --max-time "$REQUEST_TIMEOUT" "http://127.0.0.1:$PORT/actuator/health/liveness" 2>/dev/null | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
    && timeout --signal=TERM --kill-after=1s "${REQUEST_TIMEOUT}s" "${COMPOSE[@]}" exec -T "$SERVICE" curl --fail --silent --show-error --connect-timeout 2 --max-time "$REQUEST_TIMEOUT" "http://127.0.0.1:$PORT/actuator/health/readiness" 2>/dev/null | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    printf 'health_liveness=verified health_readiness=verified\n'; exit 0
  fi
  (( attempt < ATTEMPTS )) && { sleep_for=$INTERVAL; (( $(remaining) < sleep_for )) && sleep_for=$(remaining); (( sleep_for > 0 )) && sleep "$sleep_for"; }
done
fail "health gate timed out"
