#!/usr/bin/env bash
# Stages an APK for publishing: copies it as wallpaper-changer-<versionCode>.apk
# into <out-dir> and writes update.json beside it, the file the app reads to
# know whether a newer build exists. Version fields come from the APK itself
# (aapt2), so they are what gets installed, not what the build meant to set.
#
# Usage: write-update-json.sh <release|debug> <apk> <out-dir>
# Prints the staged APK's path.
set -euo pipefail

channel="${1:?usage: $0 <release|debug> <apk> <out-dir>}"
apk="${2:?usage: $0 <release|debug> <apk> <out-dir>}"
out="${3:?usage: $0 <release|debug> <apk> <out-dir>}"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?no Android SDK}}"
aapt2="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)aapt2"
badging="$("$aapt2" dump badging "$apk" | head -1)"
version_code="$(sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p" <<< "$badging")"
version_name="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")"
package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
[ -n "$version_code" ] && [ -n "$package" ] || { echo "Could not read the version of $apk: $badging" >&2; exit 1; }

mkdir -p "$out"
name="wallpaper-changer-$version_code.apk"
cp "$apk" "$out/$name"

jq -n \
  --arg channel "$channel" \
  --arg package "$package" \
  --argjson versionCode "$version_code" \
  --arg versionName "$version_name" \
  --arg commit "$(git rev-parse HEAD)" \
  --arg branch "${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD)}" \
  --arg apk "$name" \
  --arg sha256 "$(sha256sum "$out/$name" | cut -d' ' -f1)" \
  --arg notes "$(git log -1 --format=%s)" \
  --arg builtAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{channel: $channel, package: $package, versionCode: $versionCode, versionName: $versionName,
    commit: $commit, branch: $branch, apk: $apk, sha256: $sha256, notes: $notes, builtAt: $builtAt}' \
  > "$out/update.json"

cat "$out/update.json" >&2
echo "$out/$name"
