#!/usr/bin/env bash
# Source-only offline translation engine. No Maven credentials or binary downloads.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/translation-native/engine"
pin=2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4 # translate-kit v0.1.0
if [[ ! -d "$dest/.git" ]]; then
  git clone --no-checkout https://github.com/marcosholgado/translate-kit.git "$dest"
fi
if [[ -f "$dest/.message-center-ready" ]] && [[ "$(git -C "$dest" rev-parse HEAD)" == "$pin" ]]; then exit 0; fi
git -C "$dest" checkout --detach "$pin"
git -C "$dest" submodule update --init --recursive third_party/cld2
git -C "$dest" submodule update --init third_party/translations
git -C "$dest/third_party/translations" submodule update --init --recursive \
  inference/3rd_party/ssplit-cpp \
  inference/marian-fork/src/3rd_party/sentencepiece \
  inference/marian-fork/src/3rd_party/intgemm \
  inference/marian-fork/src/3rd_party/ruy \
  inference/marian-fork/src/3rd_party/simd_utils
"$dest/scripts/apply-engine-patches.sh"
touch "$dest/.message-center-ready"
