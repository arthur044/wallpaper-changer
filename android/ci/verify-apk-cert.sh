#!/usr/bin/env bash
# Fails unless the APK is signed with the expected key of its channel. A CI
# build signed with any other key could never update the installed app, so
# it must not be published.
#
# Usage: verify-apk-cert.sh <release|debug> <apk>
set -euo pipefail

# SHA-256 of each signing certificate: public (any installed APK shows it).
declare -A expected=(
  [release]=1a2bb41a2837431593f4dd683b867b15944d16de2c61475b57682a25832d23b9
  [debug]=1075618f874f77e9962274f803850af4ee552fe66fc0167ecbaadf9f2c219fdf
)

channel="${1:?usage: $0 <release|debug> <apk>}"
apk="${2:?usage: $0 <release|debug> <apk>}"
want="${expected[$channel]:?unknown channel: $channel}"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?no Android SDK}}"
apksigner="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)apksigner"

certs="$("$apksigner" verify --print-certs "$apk")"
# The signer label varies between build-tools versions ("Signer #1", or one
# per scheme like "V3.0 Signer:"); every signer must carry the same certificate.
got="$(sed -n 's/^.*Signer.* certificate SHA-256 digest: //p' <<< "$certs" | sort -u)"
if [ -z "$got" ]; then
  echo "No certificate found for $apk. apksigner said:" >&2
  echo "$certs" >&2
  exit 1
fi
if [ "$got" != "$want" ]; then
  echo "$apk is signed with the wrong key for $channel: $got, expected $want" >&2
  exit 1
fi
echo "$apk: signed with the $channel key"
