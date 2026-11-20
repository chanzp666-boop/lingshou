#!/usr/bin/env bash
# 生成 App 编译期用的 BYD framework stub jar（不进 APK，只用 compileOnly）。
#
# 目的：App 进程内要 `extends AbsBYDAutoStatisticListener` 才能拿到框架的**具名回调**
# （迪友 com.dy.launcher 的 a4.m 就是这么干的，见 docs/12 §9）。
# 这些类在车机 bootclasspath 里，编译期只需要签名。
#
# 产物：app/libs/byd-framework-stub.jar
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
SDK_DIR=$(awk -F= '/sdk.dir/ {print $2}' local.properties 2>/dev/null || true)
[ -n "$SDK_DIR" ] || SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR=$(ls -d "$SDK_DIR"/platforms/android-34/android.jar 2>/dev/null | head -1)
[ -n "$ANDROID_JAR" ] || ANDROID_JAR=$(ls -d "$SDK_DIR"/platforms/android-*/android.jar 2>/dev/null | tail -1)
[ -n "$ANDROID_JAR" ] || { echo "[stub] 找不到 android.jar（设 ANDROID_HOME 或先跑 tools/build.sh）" >&2; exit 1; }

OUT=build/framework-stub
rm -rf "$OUT"; mkdir -p "$OUT" app/libs
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" \
  -d "$OUT" $(find helper/stub -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true
"$JAVA_HOME/bin/jar" cf app/libs/byd-framework-stub.jar -C "$OUT" android
echo "[stub] -> app/libs/byd-framework-stub.jar ($(stat -f%z app/libs/byd-framework-stub.jar) B)"
