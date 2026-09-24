#!/usr/bin/env bash
# The tag of a branch's debug pre-release: debug-<slug>, where the slug keeps
# lowercase letters, digits, dots and dashes ("ci/Android_spike" becomes
# "debug-ci-android-spike"). Lossy, so the exact branch name lives in the
# release title and update.json.
set -euo pipefail
branch="${1:?usage: $0 <branch>}"
slug="$(tr '[:upper:]' '[:lower:]' <<< "$branch" | sed -E 's/[^a-z0-9.-]+/-/g; s/^-+//; s/-+$//')"
echo "debug-${slug:-branch}"
