#!/usr/bin/env bash
# Recreates the signing setup on a CI runner from GitHub Secrets: the key
# files go to $RUNNER_TEMP (outside the checkout, gone with the runner) and
# android/keystore.properties points at them. Nothing here is ever committed.
#
# Env (each group optional, but all or nothing):
#   RELEASE_KEYSTORE_B64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
#   DEBUG_KEYSTORE_B64
set -euo pipefail

dir="${RUNNER_TEMP:?RUNNER_TEMP is not set: this script is for CI}"
props="$(dirname "$0")/../keystore.properties"
: > "$props"

release_vars=(RELEASE_KEYSTORE_B64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD)
set_count=0
for name in "${release_vars[@]}"; do
  [ -n "${!name:-}" ] && set_count=$((set_count + 1))
done
if [ "$set_count" -eq "${#release_vars[@]}" ]; then
  base64 -d <<< "$RELEASE_KEYSTORE_B64" > "$dir/release.jks"
  {
    echo "storeFile=$dir/release.jks"
    echo "storePassword=$RELEASE_KEYSTORE_PASSWORD"
    echo "keyAlias=$RELEASE_KEY_ALIAS"
    echo "keyPassword=$RELEASE_KEY_PASSWORD"
  } >> "$props"
  echo "Release key: set up"
elif [ "$set_count" -ne 0 ]; then
  echo "Release signing secrets are incomplete ($set_count of ${#release_vars[@]} set)" >&2
  exit 1
fi

if [ -n "${DEBUG_KEYSTORE_B64:-}" ]; then
  base64 -d <<< "$DEBUG_KEYSTORE_B64" > "$dir/debug.jks"
  echo "debugStoreFile=$dir/debug.jks" >> "$props"
  echo "Shared debug key: set up"
fi
