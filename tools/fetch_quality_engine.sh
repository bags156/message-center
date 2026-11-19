#!/usr/bin/env bash
# Source-built llama.cpp, fixed revision. No model or message text is fetched by the runtime.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/quality-native/engine"
pin=78e05298f0d68abff41d08d79fa7469264b2aff0
if [[ -f "$dest/.message-center-ready" ]] && [[ "$(cat "$dest/.message-center-ready")" == "$pin" ]]; then exit 0; fi
mkdir -p "$dest"
curl --fail --location --retry 3 "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/$pin" | tar -xz --strip-components=1 -C "$dest"
printf '%s' "$pin" > "$dest/.message-center-ready"
