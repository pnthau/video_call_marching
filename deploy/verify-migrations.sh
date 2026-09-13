#!/usr/bin/env bash
set -Eeuo pipefail
usage() { echo "usage: $0 --target-id isolated-test-* --host HOST --port PORT --database DB --user USER --password-env NAME" >&2; exit 2; }
TARGET_ID= HOST= PORT= DATABASE= USER= PASSWORD_ENV=
while (($#)); do
  case "$1" in
    --target-id) TARGET_ID=${2-}; shift 2;; --host) HOST=${2-}; shift 2;; --port) PORT=${2-}; shift 2;;
    --database) DATABASE=${2-}; shift 2;; --user) USER=${2-}; shift 2;; --password-env) PASSWORD_ENV=${2-}; shift 2;; *) usage;;
esac
done
[[ "$TARGET_ID" == isolated-test-* && "$TARGET_ID" != *development* && "$TARGET_ID" != *production* ]] || { echo "refusing non-isolated target" >&2; exit 3; }
[[ -n "$HOST" && "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1 && "$PORT" -le 65535 && -n "$DATABASE" && -n "$USER" ]] || { echo "database verification parameters are invalid" >&2; exit 3; }
[[ "$PASSWORD_ENV" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || { echo "password environment variable name is invalid" >&2; exit 3; }
[[ -n "${!PASSWORD_ENV-}" ]] || { echo "password environment variable is unset" >&2; exit 3; }
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ERROR_FILE=$(mktemp)
cleanup() { rm -f -- "$ERROR_FILE"; }
trap cleanup EXIT
declare -a MIGRATIONS=(
  "V1__initial_schema.sql:3C34FFAC1CCD41525A52FF55F63A0201BCDF2D481567C5109C531FA2D6746429"
  "V2__lifecycle_v2_schema.sql:C0E67F28107DAECA5B666ACC2DF0803C62CCC356439455A1EDCED2D2A2F03D73"
  "V3__lifecycle_v2_data_migration.sql:04377556DDBC2225B9D0C3CD127FE9D6BB23D1F9E08412CAE5F73ADF9BE24937"
  "V4__admin_rubric_schema.sql:986366E2021B6F4D716A6C11F7EFEFBB9616BE5C0AE3532F5940427B274A607E"
)
for entry in "${MIGRATIONS[@]}"; do
  file=${entry%%:*}; expected=${entry##*:}
  actual=$(sha256sum "$ROOT/src/main/resources/db/migration/$file" | cut -d ' ' -f1 | tr '[:lower:]' '[:upper:]')
  [[ "$actual" == "$expected" ]] || { echo "immutable migration hash validation failed" >&2; exit 4; }
done
if ! RESULT=$(timeout --signal=TERM 15s mysql --connect-timeout=5 --batch --skip-column-names --host="$HOST" --port="$PORT" --user="$USER" "$DATABASE" -e "SELECT installed_rank, version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank" 2>"$ERROR_FILE"); then
  echo "migration history query failed" >&2
  exit 5
fi
EXPECTED=$'1\t1\tV1__initial_schema.sql\t1483811033\t1\n2\t2\tV2__lifecycle_v2_schema.sql\t-1419172143\t1\n3\t3\tV3__lifecycle_v2_data_migration.sql\t2086992823\t1\n4\t4\tV4__admin_rubric_schema.sql\t757996464\t1'
[[ "$RESULT" == "$EXPECTED" ]] || { echo "migration inventory/checksum validation failed" >&2; exit 4; }
printf 'migration_history=V1-V4 checksum_validation=passed file_hashes=verified hibernate_ddl=validate\n'
