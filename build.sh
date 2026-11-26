#!/usr/bin/env bash
# Build the DoNotTryAccessibility Zygisk module (no manager APK).
# Requirements: JDK 17+, Android SDK (build-tools, platform, NDK), CMake, Ninja, zip.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
PLATFORM_JAR="${PLATFORM_JAR:-$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
CMAKE="${CMAKE:-$(command -v cmake || true)}"
NINJA="${NINJA:-$(command -v ninja || true)}"

if [ -z "$CMAKE" ]; then
  CMAKE="$(ls -d "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$NINJA" ]; then
  NINJA="$(ls -d "$SDK"/cmake/*/bin/ninja 2>/dev/null | sort -V | tail -1)"
fi

for tool in "$PLATFORM_JAR" "$BUILD_TOOLS/d8" "$NDK/build/cmake/android.toolchain.cmake" "$CMAKE" "$NINJA"; do
  if [ ! -e "$tool" ]; then
    echo "Missing required tool: $tool" >&2
    exit 1
  fi
done

VERSION="2.0.0"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
mkdir -p "$BUILD" "$DIST"

echo "==> 1/3 compile hook dex"
rm -rf "$BUILD/hook-classes" "$BUILD/hook-dex"
mkdir -p "$BUILD/hook-classes" "$BUILD/hook-dex"

mapfile -t HOOK_SOURCES < <(find "$ROOT/hook/src" -name '*.java')
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$PLATFORM_JAR" \
  -encoding UTF-8 -d "$BUILD/hook-classes" "${HOOK_SOURCES[@]}"

jar --create --file "$BUILD/hook.jar" -C "$BUILD/hook-classes" .
"$BUILD_TOOLS/d8" --min-api 27 --lib "$PLATFORM_JAR" \
  --output "$BUILD/hook-dex" "$BUILD/hook.jar"

test -f "$BUILD/hook-dex/classes.dex"

echo "==> 2/3 embed hook dex"
python3 - "$BUILD/hook-dex/classes.dex" "$ROOT/native/src/dex_data.cpp" <<'PY'
import sys

data = open(sys.argv[1], 'rb').read()
with open(sys.argv[2], 'w', newline='\n') as f:
    f.write('extern "C" {\n')
    f.write('extern const unsigned char dnta_dex[] = {\n')
    for i in range(0, len(data), 16):
        f.write(','.join('0x%02x' % b for b in data[i:i + 16]) + ',\n')
    f.write('};\n')
    f.write('extern const unsigned int dnta_dex_len = %du;\n' % len(data))
    f.write('}\n')
PY

echo "==> 3/3 build native Zygisk libraries and package the module"
STAGE="$BUILD/module"
rm -rf "$STAGE"
mkdir -p "$STAGE/zygisk"

for abi in arm64-v8a armeabi-v7a; do
  native_build="$BUILD/native-$abi"
  rm -rf "$native_build"
  "$CMAKE" -S "$ROOT/native" -B "$native_build" -G Ninja \
    "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake" \
    "-DANDROID_ABI=$abi" \
    "-DANDROID_PLATFORM=android-27" \
    "-DANDROID_STL=c++_static" \
    "-DCMAKE_MAKE_PROGRAM=$NINJA" \
    "-DCMAKE_BUILD_TYPE=Release"
  "$CMAKE" --build "$native_build"
  cp "$native_build/libdnta.so" "$STAGE/zygisk/$abi.so"
done

cp -R "$ROOT/module/." "$STAGE/"

ZIP="$DIST/DoNotTryAccessibility-zygisk-v$VERSION.zip"
rm -f "$ZIP"
(
  cd "$STAGE"
  zip -r -q "$ZIP" .
)

echo
echo "Build finished: $ZIP"
ls -lh "$ZIP"
