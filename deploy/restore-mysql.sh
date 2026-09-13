#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
usage() { echo "usage: $0 --target-id isolated-test-* --host HOST --port PORT --database DB --user USER --password-env NAME --gpg-passphrase-file ABSOLUTE_FILE --backup-root ABSOLUTE_DIR --source ABSOLUTE_BUNDLE_ARCHIVE" >&2; exit 2; }
die() { echo "$1" >&2; exit "${2:-3}"; }
canonical_dir() { realpath -e -- "$1" 2>/dev/null; }
assert_real_root() {
  local root="$1" current="/" part
  [[ "$root" = /* && -d "$root" && ! -L "$root" ]] || die "approved backup root is invalid"
  while IFS= read -r part; do
    [[ -z "$part" ]] && continue
    current="${current%/}/$part"
    [[ ! -L "$current" ]] || die "approved backup root contains a symlink component"
  done < <(printf '%s\n' "${root#/}" | tr '/' '\n')
  [[ "$(stat -c '%u' -- "$root")" == "$(id -u)" ]] || die "approved backup root owner is invalid"
  (( ((8#$(stat -c '%a' -- "$root")) & 0022) == 0 )) || die "approved backup root permissions are too broad"
}
TARGET_ID= HOST= PORT= DATABASE= USER= PASSWORD_ENV= GPG_PASSPHRASE_FILE= BACKUP_ROOT= SOURCE=
while (($#)); do
  case "$1" in
    --target-id) TARGET_ID=${2-}; shift 2;; --host) HOST=${2-}; shift 2;; --port) PORT=${2-}; shift 2;;
    --database) DATABASE=${2-}; shift 2;; --user) USER=${2-}; shift 2;; --password-env) PASSWORD_ENV=${2-}; shift 2;;
    --gpg-passphrase-file) GPG_PASSPHRASE_FILE=${2-}; shift 2;;
    --backup-root) BACKUP_ROOT=${2-}; shift 2;;
    --source) SOURCE=${2-}; shift 2;; *) usage;;
  esac
done
[[ "$TARGET_ID" == isolated-test-* && "$TARGET_ID" != *development* && "$TARGET_ID" != *production* ]] || { echo "refusing restore outside isolated test target" >&2; exit 3; }
[[ "$SOURCE" = /* && -f "$SOURCE" && "$SOURCE" == */backup.sql.gz.gpg ]] || { echo "source must be an external encrypted bundle archive" >&2; exit 3; }
[[ ! -L "$SOURCE" && -f "$SOURCE" ]] || { echo "source must be a regular file" >&2; exit 3; }
BUNDLE=$(dirname -- "$SOURCE")
[[ "$BACKUP_ROOT" = /* && -n "$BACKUP_ROOT" ]] || usage
APPROVED_ROOT="$BACKUP_ROOT"
assert_real_root "$APPROVED_ROOT"
ROOT_CANON=$(canonical_dir "$APPROVED_ROOT") || die "approved backup root cannot be canonicalized"
[[ "$ROOT_CANON" == "$APPROVED_ROOT" && "$SOURCE" == "$ROOT_CANON"/* ]] || die "source is outside approved backup root"
[[ "$BUNDLE" == "$ROOT_CANON"/* && "$(dirname -- "$BUNDLE")" == "$ROOT_CANON" && "$BUNDLE" == *.bundle && ! -L "$BUNDLE" && -d "$BUNDLE" ]] || { echo "source must be in a direct completed bundle" >&2; exit 3; }
[[ "$(realpath -e -- "$BUNDLE")" == "$BUNDLE" && "$(realpath -e -- "$SOURCE")" == "$SOURCE" ]] || die "bundle path contains a symlink or non-canonical component"
[[ "$(basename -- "$BUNDLE")" =~ ^isolated-test-[A-Za-z0-9_-]+-[A-Za-z0-9]+\.bundle$ ]] || die "bundle name is invalid"
[[ "$(find "$BUNDLE" -mindepth 1 -maxdepth 1 -type f | wc -l)" == 3 && "$(find "$BUNDLE" -mindepth 1 -maxdepth 1 ! -type f | wc -l)" == 0 ]] || die "bundle inventory is not exact"
[[ "$GPG_PASSPHRASE_FILE" = /* && ! -L "$GPG_PASSPHRASE_FILE" && -f "$GPG_PASSPHRASE_FILE" ]] || { echo "GnuPG passphrase file must be a regular file" >&2; exit 3; }
[[ "$(stat -c '%a' -- "$GPG_PASSPHRASE_FILE")" == 600 || "$(stat -c '%a' -- "$GPG_PASSPHRASE_FILE")" == 400 ]] || { echo "GnuPG passphrase file permissions are too broad" >&2; exit 3; }
[[ "$(stat -c '%u' -- "$GPG_PASSPHRASE_FILE")" == "$(id -u)" ]] || { echo "GnuPG passphrase file owner is invalid" >&2; exit 3; }
GPG_PASSPHRASE_CANON=$(realpath -e -- "$GPG_PASSPHRASE_FILE" 2>/dev/null) || die "GnuPG passphrase file cannot be canonicalized"
[[ "$GPG_PASSPHRASE_CANON" == "$GPG_PASSPHRASE_FILE" ]] || die "GnuPG passphrase path contains a symlink component"
command -v gpg >/dev/null || { echo "GnuPG is required" >&2; exit 3; }
[[ -n "$HOST" && -n "$PORT" && -n "$DATABASE" && -n "$USER" && -n "$PASSWORD_ENV" ]] || usage
[[ -n "${!PASSWORD_ENV-}" ]] || { echo "password environment variable is unset" >&2; exit 3; }
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
[[ -z "$REPO_ROOT" || "$SOURCE" != "$REPO_ROOT"/* ]] || { echo "restore source must be outside repository" >&2; exit 3; }
[[ -z "$REPO_ROOT" || "$GPG_PASSPHRASE_CANON" != "$REPO_ROOT"/* ]] || { echo "GnuPG passphrase file must be outside repository" >&2; exit 3; }
MARKER="$SOURCE.complete"
MANIFEST="$SOURCE.sha256"
[[ ! -L "$MARKER" && -f "$MARKER" ]] || { echo "completion marker is required" >&2; exit 3; }
[[ ! -L "$MANIFEST" && -f "$MANIFEST" ]] || { echo "integrity manifest is required" >&2; exit 3; }
[[ "$(wc -l < "$MARKER")" == 1 && "$(wc -l < "$MANIFEST")" == 1 ]] || { echo "bundle metadata must contain one line" >&2; exit 3; }
MARKER_CONTENT=$(<"$MARKER")
SOURCE_BASENAME=$(basename -- "$SOURCE")
[[ "$MARKER_CONTENT" =~ ^complete\ archive=([^[:space:]]+)\ sha256=([a-f0-9]{64})\ encryption=gpg-aes256\ integrity=verified$ ]] || { echo "completion marker is invalid" >&2; exit 3; }
[[ "${BASH_REMATCH[1]}" == "$SOURCE_BASENAME" ]] || { echo "completion marker archive binding is invalid" >&2; exit 3; }
MARKER_DIGEST=${BASH_REMATCH[2]}
MANIFEST_CONTENT=$(<"$MANIFEST")
[[ "$MANIFEST_CONTENT" =~ ^([[:xdigit:]]{64})[[:space:]][[:space:]]([^[:space:]]+)$ ]] || { echo "integrity manifest is invalid" >&2; exit 3; }
[[ "${BASH_REMATCH[1]}" == "${BASH_REMATCH[1],,}" ]] || { echo "integrity digest must be lowercase" >&2; exit 3; }
[[ "${BASH_REMATCH[2]}" == "$SOURCE_BASENAME" ]] || { echo "integrity manifest archive binding is invalid" >&2; exit 3; }
MANIFEST_DIGEST=${BASH_REMATCH[1]}
EXPECTED_DIGEST=$(sha256sum -- "$SOURCE" | cut -d ' ' -f1)
[[ "$MARKER_DIGEST" == "$EXPECTED_DIGEST" ]] || { echo "completion marker digest mismatch" >&2; exit 3; }
[[ "$MANIFEST_DIGEST" == "$EXPECTED_DIGEST" ]] || { echo "integrity checksum mismatch" >&2; exit 3; }
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/restore.XXXXXX")
TMP_GZIP="$TMP_DIR/restore.sql.gz"
cleanup() { local status=$?; rm -f -- "$TMP_GZIP"; rmdir -- "$TMP_DIR" 2>/dev/null || true; return "$status"; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$GPG_PASSPHRASE_FILE" --decrypt --output "$TMP_GZIP" -- "$SOURCE"
gzip -t -- "$TMP_GZIP"
gzip -dc -- "$TMP_GZIP" | MYSQL_PWD=${!PASSWORD_ENV} mysql --host="$HOST" --port="$PORT" --user="$USER" "$DATABASE"
printf 'restore_completed target_class=isolated-test encryption=gpg-aes256 integrity=verified\n'
