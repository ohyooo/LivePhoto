#!/usr/bin/env bash
# Retry a known plugin-resolution outage before any Gradle task executed.
set -eu

logs=()
while (($#)) && [[ $1 != -- ]]; do
  logs+=("$1")
  shift
done
if (( ${#logs[@]} == 0 || $# < 2 )); then
  echo 'Usage: run-web-gradle.sh LOG [LOG ...] -- GRADLE [ARGS ...]' >&2
  exit 2
fi
shift
capture=$(mktemp)
trap 'rm -f "$capture"' EXIT
for log in "${logs[@]}"; do : > "$log"; done
refresh=()
for attempt in 1 2 3; do
  : > "$capture"
  {
    printf '\nWeb Gradle attempt %s/3\n' "$attempt"
  } | tee -a -- "${logs[@]}"
  set +e
  "$@" "${refresh[@]}" 2>&1 | tee -a -- "$capture" "${logs[@]}"
  result=("${PIPESTATUS[@]}")
  set -e
  (( result[1] == 0 )) || exit "${result[1]}"
  (( result[0] != 0 )) || exit 0
  if (( attempt == 3 )) ||
     grep -q '^> Task ' "$capture" ||
     ! grep -Fq "Plugin [id: 'org.jetbrains.kotlin.multiplatform', version:" "$capture" ||
     ! grep -Fq 'could not resolve plugin artifact' "$capture"; then
    exit "${result[0]}"
  fi
  echo 'Kotlin Multiplatform plugin resolution failed before tasks ran; retrying with refreshed dependency metadata.' |
    tee -a -- "${logs[@]}"
  sleep "$((15 * attempt))"
  refresh=(--refresh-dependencies)
done
