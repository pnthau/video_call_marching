#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
usage() { echo "usage: $0 --target-id isolated-test-* --host HOST --port PORT --database DB --user USER --password-env NAME --gpg-passphrase-file ABSOLUTE_FILE --output-dir ABSOLUTE_DIR" >&2; exit 2; }
die() { echo "$1" >&2; exit "${2:-3}"; }
canonical_dir() { realpath -e -- "$1" 2>/dev/null; }
assert_real_root() {
  local root="$1" current="/" part
  [[ "$root" = /* && -d "$root" && ! -L "$root" ]] || die "backup directory must be a real directory"
  while IFS= read -r part; do
    [[ -z "$part" ]] && continue
    current="${current%/}/$part"
    [[ ! -L "$current" ]] || die "backup path contains a symlink component"
  done < <(printf '%s\n' "${root#/}" | tr '/' '\n')
  [[ "$(stat -c '%u' -- "$root")" == "$(id -u)" ]] || die "backup directory owner is invalid"
  (( ((8#$(stat -c '%a' -- "$root")) & 0022) == 0 )) || die "backup directory permissions are too broad"
}
TARGET_ID= HOST= PORT= DATABASE= USER= PASSWORD_ENV= GPG_PASSPHRASE_FILE= OUTPUT_DIR=
while (($#)); do
  case "$1" in
    --target-id) TARGET_ID=${2-}; shift 2;; --host) HOST=${2-}; shift 2;; --port) PORT=${2-}; shift 2;;
    --database) DATABASE=${2-}; shift 2;; --user) USER=${2-}; shift 2;; --password-env) PASSWORD_ENV=${2-}; shift 2;;
    --gpg-passphrase-file) GPG_PASSPHRASE_FILE=${2-}; shift 2;;
    --output-dir) OUTPUT_DIR=${2-}; shift 2;; *) usage;;
  esac
done
[[ "$TARGET_ID" == isolated-test-* && "$TARGET_ID" != *development* && "$TARGET_ID" != *production* && "$TARGET_ID" != */* ]] || { echo "refusing non-isolated target" >&2; exit 3; }
[[ "$OUTPUT_DIR" = /* ]] || { echo "backup directory must be absolute" >&2; exit 3; }
[[ "$GPG_PASSPHRASE_FILE" = /* && ! -L "$GPG_PASSPHRASE_FILE" && -f "$GPG_PASSPHRASE_FILE" ]] || { echo "GnuPG passphrase file must be a regular file" >&2; exit 3; }
[[ "$(stat -c '%a' -- "$GPG_PASSPHRASE_FILE")" == 600 || "$(stat -c '%a' -- "$GPG_PASSPHRASE_FILE")" == 400 ]] || { echo "GnuPG passphrase file permissions are too broad" >&2; exit 3; }
[[ "$(stat -c '%u' -- "$GPG_PASSPHRASE_FILE")" == "$(id -u)" ]] || { echo "GnuPG passphrase file owner is invalid" >&2; exit 3; }
GPG_PASSPHRASE_CANON=$(realpath -e -- "$GPG_PASSPHRASE_FILE" 2>/dev/null) || die "GnuPG passphrase file cannot be canonicalized"
[[ "$GPG_PASSPHRASE_CANON" == "$GPG_PASSPHRASE_FILE" ]] || die "GnuPG passphrase path contains a symlink component"
command -v gpg >/dev/null || { echo "GnuPG is required" >&2; exit 3; }
command -v flock >/dev/null || { echo "flock is required" >&2; exit 3; }
[[ -n "$HOST" && -n "$PORT" && -n "$DATABASE" && -n "$USER" && -n "$PASSWORD_ENV" ]] || usage
[[ -n "${!PASSWORD_ENV-}" ]] || { echo "password environment variable is unset" >&2; exit 3; }
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
[[ -z "$REPO_ROOT" || "$OUTPUT_DIR" != "$REPO_ROOT"/* ]] || { echo "backup storage must be outside repository" >&2; exit 3; }
[[ -z "$REPO_ROOT" || "$GPG_PASSPHRASE_CANON" != "$REPO_ROOT"/* ]] || { echo "GnuPG passphrase file must be outside repository" >&2; exit 3; }
assert_real_root "$OUTPUT_DIR"
OUTPUT_ROOT=$(canonical_dir "$OUTPUT_DIR") || die "backup directory cannot be canonicalized"
[[ "$OUTPUT_ROOT" == "$OUTPUT_DIR" ]] || die "backup directory path must be canonical"
TMP_DIR= STAGE_DIR= RETENTION_FD= DEST_BUNDLE= PUBLISHED=0
cleanup() {
  local status=$? cleanup_dir
  if [[ -n "$RETENTION_FD" ]]; then
    exec {RETENTION_FD}>&- || status=7
    RETENTION_FD=
  fi
  if [[ -n "$TMP_DIR" && "$TMP_DIR" == "$OUTPUT_ROOT/.work-$TARGET_ID."* && -d "$TMP_DIR" && ! -L "$TMP_DIR" ]]; then
    cleanup_dir=$(realpath -e -- "$TMP_DIR" 2>/dev/null || true)
    if [[ "$cleanup_dir" == "$TMP_DIR" ]]; then
      rm -rf -- "$TMP_DIR" || status=7
    fi
  fi
  return "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

[[ ! -L "$OUTPUT_DIR/.retention.lock" ]] || { echo "retention lock path is a symlink" >&2; exit 6; }
exec {RETENTION_FD}>"$OUTPUT_DIR/.retention.lock"
flock -w 30 "$RETENTION_FD" || { echo "retention lock timeout" >&2; exit 6; }

TMP_DIR=$(mktemp -d "$OUTPUT_DIR/.work-${TARGET_ID}.XXXXXX")
TMP_BASENAME=$(basename -- "$TMP_DIR")
TMP_SUFFIX=${TMP_BASENAME##*.}
STAGE_DIR="$TMP_DIR/${TARGET_ID}-${TMP_SUFFIX}.bundle.stage"
DEST_BUNDLE="$OUTPUT_DIR/${TARGET_ID}-${TMP_SUFFIX}.bundle"
DEST_BASENAME=backup.sql.gz.gpg

mkdir -- "$STAGE_DIR"
TMP_SQL="$TMP_DIR/backup.sql"; TMP_GZIP="$TMP_DIR/backup.sql.gz"; TMP_ENCRYPTED="$STAGE_DIR/$DEST_BASENAME"; TMP_SHA256="$TMP_ENCRYPTED.sha256"; TMP_COMMIT="$TMP_ENCRYPTED.complete"

MYSQL_PWD=${!PASSWORD_ENV} mysqldump --host="$HOST" --port="$PORT" --user="$USER" \
  --single-transaction --routines --triggers --hex-blob --no-tablespaces "$DATABASE" > "$TMP_SQL"
gzip -n -c -- "$TMP_SQL" > "$TMP_GZIP"
gzip -t -- "$TMP_GZIP"
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$GPG_PASSPHRASE_FILE" --symmetric --cipher-algo AES256 --output "$TMP_ENCRYPTED" "$TMP_GZIP"
[[ -s "$TMP_ENCRYPTED" ]] || { echo "encryption produced no artifact" >&2; exit 5; }
sha256sum -- "$TMP_ENCRYPTED" > "$TMP_SHA256"
CHECKSUM=$(cut -d ' ' -f1 < "$TMP_SHA256")
printf '%s  %s\n' "$CHECKSUM" "$DEST_BASENAME" > "$TMP_SHA256"

[[ ! -e "$DEST_BUNDLE" ]] || { echo "backup destination already exists" >&2; exit 4; }
printf 'complete archive=%s sha256=%s encryption=gpg-aes256 integrity=verified\n' "$DEST_BASENAME" "$CHECKSUM" > "$TMP_COMMIT"
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$GPG_PASSPHRASE_FILE" --decrypt --output /dev/null "$TMP_ENCRYPTED" >/dev/null 2>&1
(cd "$STAGE_DIR" && sha256sum --check "$(basename -- "$TMP_SHA256")" >/dev/null)
[[ "$(find "$STAGE_DIR" -mindepth 1 -maxdepth 1 -type f | wc -l)" == 3 ]] || { echo "bundle inventory is invalid" >&2; exit 5; }
[[ ! -e "$STAGE_DIR/backup.sql" && ! -e "$STAGE_DIR/backup.sql.gz" ]] || { echo "plaintext entered publication stage" >&2; exit 5; }
[[ -f "$STAGE_DIR/$DEST_BASENAME" && -f "$TMP_SHA256" && -f "$TMP_COMMIT" && ! -L "$STAGE_DIR/$DEST_BASENAME" && ! -L "$TMP_SHA256" && ! -L "$TMP_COMMIT" ]] || { echo "bundle inventory is invalid" >&2; exit 5; }
sync -f "$STAGE_DIR" 2>/dev/null || true
mv -n -- "$STAGE_DIR" "$DEST_BUNDLE"; [[ ! -e "$STAGE_DIR" ]] || { echo "bundle publication collision" >&2; exit 5; }; PUBLISHED=1
rm -rf -- "$TMP_DIR" || { echo "owned plaintext cleanup failed" >&2; exit 7; }; TMP_DIR=

declare -a VALID_BUNDLES=()
declare -A BUNDLE_PATH DAILY_KEEP WEEKLY_KEEP KEEP_PATH DELETE_PATH
mapfile -d '' -t CANDIDATES < <(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -type d -name 'isolated-test-*.bundle' -printf '%p\0' | sort -z)
for PATHNAME in "${CANDIDATES[@]}"; do
  BASENAME=$(basename -- "$PATHNAME")
  [[ "$BASENAME" =~ ^isolated-test-[A-Za-z0-9_-]+-[A-Za-z0-9]+\.bundle$ && ! -L "$PATHNAME" && -d "$PATHNAME" ]] || continue
  CANONICAL_PATH=$(realpath -e -- "$PATHNAME" 2>/dev/null) || continue
  [[ "$CANONICAL_PATH" == "$OUTPUT_ROOT/$BASENAME" && "$PATHNAME" == "$CANONICAL_PATH" ]] || continue
  ARCHIVE="$PATHNAME/backup.sql.gz.gpg"; MARKER_PATH="$ARCHIVE.complete"; MANIFEST_PATH="$ARCHIVE.sha256"
  [[ "$(find "$PATHNAME" -mindepth 1 -maxdepth 1 -type f | wc -l)" == 3 && "$(find "$PATHNAME" -mindepth 1 -maxdepth 1 ! -type f | wc -l)" == 0 ]] || continue
  [[ ! -L "$ARCHIVE" && -f "$ARCHIVE" && ! -L "$MARKER_PATH" && -f "$MARKER_PATH" && ! -L "$MANIFEST_PATH" && -f "$MANIFEST_PATH" ]] || continue
  MARKER_CONTENT=$(<"$MARKER_PATH")
  [[ "$MARKER_CONTENT" =~ ^complete\ archive=([^[:space:]]+)\ sha256=([[:xdigit:]]{64})\ encryption=gpg-aes256\ integrity=verified$ ]] || continue
  [[ "${BASH_REMATCH[1]}" == backup.sql.gz.gpg ]] || continue
  [[ "$(cut -d ' ' -f1 < "$MANIFEST_PATH")" == "${BASH_REMATCH[2]}" && "$(cut -d ' ' -f3- < "$MANIFEST_PATH")" == backup.sql.gz.gpg ]] || continue
  (cd "$PATHNAME" && sha256sum --check "$(basename -- "$MANIFEST_PATH")" >/dev/null 2>&1) || continue
  TIMESTAMP=$(stat -c '%Y' -- "$PATHNAME")
  DATE_UTC=$(date -u -d "@$TIMESTAMP" +%F)
  WEEK_UTC=$(date -u -d "@$TIMESTAMP" +%G-%V)
  VALID_BUNDLES+=("$TIMESTAMP|$BASENAME|$DATE_UTC|$WEEK_UTC")
  BUNDLE_PATH["$BASENAME"]="$CANONICAL_PATH"
done

IFS=$'\n' SORTED_BUNDLES=($(printf '%s\n' "${VALID_BUNDLES[@]}" | sort -t '|' -k1,1nr -k2,2))
NEWEST_DAILY_BUNDLE= NEWEST_DATE= NEWEST_WEEK=
for RECORD in "${SORTED_BUNDLES[@]}"; do
  IFS='|' read -r TIMESTAMP BASENAME DATE_UTC WEEK_UTC <<< "$RECORD"
  [[ -n "${DAILY_KEEP[$DATE_UTC]-}" ]] || DAILY_KEEP["$DATE_UTC"]="$BASENAME"
  [[ -n "$NEWEST_DAILY_BUNDLE" ]] || { NEWEST_DAILY_BUNDLE="$BASENAME"; NEWEST_DATE="$DATE_UTC"; NEWEST_WEEK="$WEEK_UTC"; }
done

mapfile -t DAILY_DATES < <(printf '%s\n' "${!DAILY_KEEP[@]}" | sort -r | head -n 7)
for DATE_UTC in "${DAILY_DATES[@]}"; do
  [[ -n "$DATE_UTC" ]] || continue
  KEEP_PATH["${DAILY_KEEP[$DATE_UTC]}"]=1
done

PREVIOUS_WEEK=
[[ -n "$NEWEST_WEEK" ]] && PREVIOUS_WEEK=$(date -u -d "${NEWEST_DATE} - 7 days" +%G-%V)
for RECORD in "${SORTED_BUNDLES[@]}"; do
  IFS='|' read -r TIMESTAMP BASENAME DATE_UTC WEEK_UTC <<< "$RECORD"
  [[ "$WEEK_UTC" == "$PREVIOUS_WEEK" && -z "${KEEP_PATH[$BASENAME]-}" ]] || continue
  WEEKLY_KEEP["$BASENAME"]=1
  KEEP_PATH["$BASENAME"]=1
  break
done

for RECORD in "${SORTED_BUNDLES[@]}"; do
  IFS='|' read -r TIMESTAMP BASENAME DATE_UTC WEEK_UTC <<< "$RECORD"
  [[ -n "${KEEP_PATH[$BASENAME]-}" ]] || DELETE_PATH["$BASENAME"]=1
done
for BASENAME in "${!DELETE_PATH[@]}"; do
  PATHNAME="${BUNDLE_PATH[$BASENAME]}"
  [[ "$PATHNAME" == "$OUTPUT_ROOT/$BASENAME" && -d "$PATHNAME" && ! -L "$PATHNAME" ]] || continue
  rm -rf -- "$PATHNAME"
done
exec {RETENTION_FD}>&-
printf 'backup_created target_class=isolated-test artifact=external integrity=verified\n'
