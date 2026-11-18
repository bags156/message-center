#!/usr/bin/env bash
# Build both APK ABIs once, shared by debug/release/test variants like the Go bridge.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$root/tools/fetch_translation_engine.sh"
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
cmake="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
for abi in arm64-v8a x86_64; do
  build="$root/translation-native/build/$abi"
  "$cmake" -S "$root/translation-native" -B "$build" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-33 \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
  "$cmake" --build "$build" --target message-translation --parallel 2
  dest="$root/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  cp "$build/libmessage-translation.so" "$dest/"
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$dest/libmessage-translation.so"
done
