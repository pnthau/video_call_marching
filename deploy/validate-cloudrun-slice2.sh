#!/usr/bin/env bash
set -u

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cloudrun_dir="$root_dir/deploy/cloudrun"
service_file=${CLOUDRUN_SERVICE_FILE:-$cloudrun_dir/service.yaml}
job_file=${CLOUDRUN_JOB_FILE:-$cloudrun_dir/migration-job.yaml}
failures=0
rendered=0

fail() { printf 'FAIL: %s\n' "$1" >&2; failures=$((failures + 1)); }
require() { grep -Fq -- "$2" "$1" || fail "$1 missing required contract: $2"; }

env_value() {
  awk -v wanted="$2" '
    $0 ~ "^[[:space:]]*-[[:space:]]+name:[[:space:]]*" wanted "[[:space:]]*$" {
      if (getline > 0 && $0 ~ /^[[:space:]]+value:/) {
        sub(/^[[:space:]]+value:[[:space:]]*/, ""); print; exit
      }
    }
  ' "$1"
}

has_profile() {
  local list="$1" wanted="$2" token
  local -a profiles
  IFS=',' read -r -a profiles <<< "$list"
  for token in "${profiles[@]}"; do
    token=$(printf '%s' "$token" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    [ "$token" = "$wanted" ] && return 0
  done
  return 1
}

check_image() {
  local file="$1" image
  image=$(sed -n 's/^[[:space:]]*image:[[:space:]]*//p' "$file" | head -n 1)
  [ -n "$image" ] || { fail "$file missing image"; return; }
  if printf '%s' "$image" | rg -q '@sha256:\$\{[^}]+\}'; then
    [ "$rendered" -eq 0 ] || fail "$file contains placeholder image digest"
    return
  fi
  if ! printf '%s' "$image" | rg -q '@sha256:[0-9A-Fa-f]{64}$'; then
    fail "$file image is not pinned to a valid sha256 digest"
  fi
}

check_profiles() {
  local list
  list=$(env_value "$job_file" SPRING_PROFILES_ACTIVE)
  if ! has_profile "$list" migration; then
    fail "$job_file must activate the migration profile"
  fi
  for profile in prod production cloudrun serving; do
    has_profile "$list" "$profile" && fail "$job_file must not activate serving profile: $profile"
  done
  [ "$(env_value "$job_file" SPRING_MAIN_WEB_APPLICATION_TYPE)" = none ] ||
    fail "$job_file must disable the web application"
}

check_placeholders() {
  [ "$rendered" -eq 1 ] || return 0
  local files=("$service_file" "$job_file")
  if rg -n -F '${' "${files[@]}" >/dev/null 2>&1 ||
     rg -n -F '__' "${files[@]}" >/dev/null 2>&1 ||
     rg -n -U '<[A-Za-z][A-Za-z0-9_.-]*>' "${files[@]}" >/dev/null 2>&1 ||
     rg -n -i -F 'REPLACE_ME' -F 'CHANGEME' -F 'DUMMY_VALUE' "${files[@]}" >/dev/null 2>&1; then
    fail 'unresolved deployment placeholder'
  fi
}

validate_files() {
  for file in service.yaml migration-job.yaml iam-plan.yaml secrets.yaml health.yaml README.md; do
    [ -f "$cloudrun_dir/$file" ] || fail "missing $file"
  done
  [ -f "$service_file" ] || fail "missing service manifest"
  [ -f "$job_file" ] || fail "missing migration Job manifest"

  require "$service_file" 'autoscaling.knative.dev/maxScale: "3"'
  require "$service_file" 'containerConcurrency: 20'
  require "$service_file" 'timeoutSeconds: 300'
  require "$service_file" 'value: cloudrun'
  require "$service_file" 'value: app_runtime'
  for name in GOOGLE_CLIENT_ID CLOUDINARY_CLOUD_NAME CLOUDINARY_API_KEY AGORA_APP_ID WEBSOCKET_ALLOWED_ORIGIN_PATTERNS; do
    require "$service_file" "- name: $name"
  done
  require "$job_file" '    spec:'
  require "$job_file" '      parallelism: 1'
  require "$job_file" '      taskCount: 1'
  require "$job_file" '          maxRetries: 1'
  require "$job_file" '          timeoutSeconds: 900'
  require "$job_file" '                  value: migration'
  require "$job_file" '                  value: app_migrator'
  require "$cloudrun_dir/iam-plan.yaml" 'roles/cloudsql.client'
  require "$cloudrun_dir/secrets.yaml" 'MYSQL_ROOT_PASSWORD'
  require "$cloudrun_dir/health.yaml" 'tlsVerification: required'

  check_profiles
  check_image "$service_file"
  check_image "$job_file"
  check_placeholders

  if rg -n -i '^[[:space:]]*(MYSQL_PASSWORD|MIGRATION_MYSQL_PASSWORD|GOOGLE_CLIENT_SECRET|CLOUDINARY_API_SECRET|AGORA_APP_CERTIFICATE):[[:space:]]+[^"$<{[:space:]]' "$cloudrun_dir" >/dev/null 2>&1; then
    fail 'possible secret payload in Cloud Run artifacts'
  fi
  if rg -n 'MYSQL_ROOT_PASSWORD' "$service_file" "$job_file" "$cloudrun_dir/iam-plan.yaml" >/dev/null 2>&1; then
    fail 'root credential appears in runtime/IAM artifact'
  fi
  [ "$failures" -eq 0 ]
}

run_case() {
  local label="$1" expected="$2" actual
  shift 2
  if "$@" >/dev/null 2>&1; then actual=0; else actual=1; fi
  total=$((total + 1))
  if [ "$actual" -eq "$expected" ]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1)); printf 'FAIL: self-test case %s expected %s got %s\n' "$label" "$expected" "$actual" >&2
  fi
}

self_test() {
  local self_dir total=0 passed=0 failed=0
  self_dir=$(mktemp -d) || { printf 'FAIL: cannot create self-test directory\n' >&2; return 1; }
  cleanup_self_test() { rm -rf -- "$self_dir"; }
  trap cleanup_self_test EXIT INT TERM
  cp -- "$service_file" "$self_dir/service.yaml"
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i \
    -e 's#${PROJECT_ID}#project#g' -e 's#${REGION}#asia-southeast1#g' \
    -e 's#${REPOSITORY}#repo#g' -e 's#${IMAGE_DIGEST}#0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef#g' \
    -e 's#${PUBLIC_BASE_URL}#https://staging.example.test#g' \
    -e 's#${GOOGLE_CLIENT_ID}#client-id#g' -e 's#${CLOUDINARY_CLOUD_NAME}#cloud#g' \
    -e 's#${CLOUDINARY_API_KEY}#api-key#g' -e 's#${AGORA_APP_ID}#agora-id#g' \
    -e 's#${WEBSOCKET_ALLOWED_ORIGIN_PATTERNS}#https://staging.example.test#g' \
    "$self_dir/service.yaml" "$self_dir/migration-job.yaml"
  run_validator() { CLOUDRUN_SERVICE_FILE="$self_dir/service.yaml" CLOUDRUN_JOB_FILE="$self_dir/migration-job.yaml" "$0" --rendered; }
  run_case positive 0 run_validator

  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i '0,/      parallelism: 1/s//    parallelism: 1/' "$self_dir/migration-job.yaml"
  run_case wrong-parallelism-level 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i '0,/      taskCount: 1/s//    taskCount: 1/' "$self_dir/migration-job.yaml"
  run_case wrong-task-count-level 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i '0,/          maxRetries: 1/s//        maxRetries: 1/' "$self_dir/migration-job.yaml"
  run_case wrong-retries-level 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i '0,/          timeoutSeconds: 900/s//        timeoutSeconds: 900/' "$self_dir/migration-job.yaml"
  run_case wrong-timeout-level 1 run_validator

  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i 's/value: migration/value: migration, cloudrun/' "$self_dir/migration-job.yaml"
  run_case serving-profile 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i 's/value: migration/value:  cloudrun ,  migration  /' "$self_dir/migration-job.yaml"
  run_case serving-profile-whitespace-order 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i 's/value: migration/value: migration, prod/' "$self_dir/migration-job.yaml"
  run_case prod-profile 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i 's#@sha256:${IMAGE_DIGEST}# :latest#' "$self_dir/migration-job.yaml"
  run_case mutable-image 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i 's#@sha256:${IMAGE_DIGEST}#@sha256:1234#' "$self_dir/migration-job.yaml"
  run_case malformed-image-digest 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  sed -i 's#@sha256:${IMAGE_DIGEST}#@sha256:__IMAGE_DIGEST__#' "$self_dir/migration-job.yaml"
  run_case placeholder-image-digest 1 run_validator
  cp -- "$job_file" "$self_dir/migration-job.yaml"
  run_case unresolved-placeholder 1 run_validator

  for marker in '\${UNRESOLVED}' '__UNRESOLVED__' '<UNRESOLVED>' 'REPLACE_ME'; do
    cp -- "$self_dir/service.yaml" "$self_dir/service.yaml.tmp"
    sed -i "0,/value: client-id/s#value: client-id#value: $marker#" "$self_dir/service.yaml"
    run_case "placeholder-$marker" 1 run_validator
    mv -- "$self_dir/service.yaml.tmp" "$self_dir/service.yaml"
  done

  for name in GOOGLE_CLIENT_ID CLOUDINARY_CLOUD_NAME CLOUDINARY_API_KEY AGORA_APP_ID WEBSOCKET_ALLOWED_ORIGIN_PATTERNS; do
    cp -- "$self_dir/service.yaml" "$self_dir/service.yaml.tmp"
    sed -i "/- name: $name/{N;d;}" "$self_dir/service.yaml"
    run_case "missing-$name" 1 run_validator
    mv -- "$self_dir/service.yaml.tmp" "$self_dir/service.yaml"
  done
  trap - EXIT INT TERM
  cleanup_self_test
  printf 'Cloud Run Slice 2 self-test: %s/%s PASS\n' "$passed" "$total"
  [ "$failed" -eq 0 ]
}

if [ "${1:-}" = '--self-test' ]; then self_test; exit $?; fi
if [ "${1:-}" = '--rendered' ]; then rendered=1; fi
validate_files
if [ "$failures" -ne 0 ]; then
  printf 'Cloud Run Slice 2 validation: FAIL (%s)\n' "$failures" >&2
  exit 1
fi
printf 'Cloud Run Slice 2 validation: PASS\n'
