#!/usr/bin/env bash
# 编译 helper 成 dex，供 App 用 adblib 推到 /data/local/tmp 后由 app_process 拉起。
#   产物：app/src/main/assets/ls-helper.dex（随 APK 一起打包）
#
# 为什么单独编：helper 跑在 shell(uid 2000) 的 app_process 里，需要能加载
# android.hardware.bydauto.* 这类 framework 类；App 进程受 hidden API 限制加载不了。
set -euo pipefail
cd "$(dirname "$0")/.."

# JAVA_HOME 自动探测：优先本机 JDK 17 常见位置，找不到再用 java_home。
if [ -z "${JAVA_HOME:-}" ]; then
  for cand in \
    "$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
    "$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)" \
    "$(/usr/libexec/java_home 2>/dev/null || true)"; do
    [ -n "$cand" ] && [ -x "$cand/bin/javac" ] && JAVA_HOME="$cand" && break
  done
fi
: "${JAVA_HOME:?找不到 JDK}"
# d8 里是裸 `exec java`（走 PATH），系统 PATH 上可能只有 JDK 8 —— 必须把选中的 JDK 顶到前面，
# 否则报 UnsupportedClassVersionError: com/android/tools/r8/D8（class file 55 vs 52）。
export PATH="$JAVA_HOME/bin:$PATH"
SDK_DIR=$(awk -F= '/sdk.dir/ {print $2}' local.properties)
BT=$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1)
ANDROID_JAR=$(ls -d "$SDK_DIR"/platforms/android-34/android.jar 2>/dev/null | head -1)
[ -n "$ANDROID_JAR" ] || ANDROID_JAR=$(ls -d "$SDK_DIR"/platforms/android-*/android.jar | tail -1)

OUT=build/helper
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" app/src/main/assets

echo "[helper] stub（编译期签名，不进 dex）"
STUBOUT=build/helper/stub
rm -rf "$STUBOUT"; mkdir -p "$STUBOUT"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" \
  -d "$STUBOUT" $(find helper/stub -name '*.java') 2>&1 | grep -v "bootstrap class path" || true
"$JAVA_HOME/bin/jar" cf "$STUBOUT/stub.jar" -C "$STUBOUT" android

echo "[helper] javac"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" \
  -classpath "$STUBOUT/stub.jar" \
  -d "$OUT/classes" $(find helper/src -name '*.java') app/src/main/java/com/byd/ls/helper/FidTable.java

echo "[helper] d8"
"$BT/d8" --min-api 29 --lib "$STUBOUT/stub.jar" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp -f "$OUT/dex/classes.dex" app/src/main/assets/ls-helper.dex

# 再打一份「只有 dex 的 APK」：实测启迪桌面用 CLASSPATH=<base.apk> 才能跑起 app_process，
# 裸 dex 在这台车上会挂住（docs/11 §4）。zip 里放 classes.dex 即可被 app_process 识别。
python3 - <<PYEOF2
import zipfile, pathlib
src = pathlib.Path("$OUT/dex/classes.dex").read_bytes()
dst = pathlib.Path("app/src/main/assets/ls-helper.apk")
# 固定时间戳：zipfile 默认写「当前时间」，会让同一份 dex 每次产出不同 sha256，
# 进而让 App 的 APK 体积/sha 每次都变（1.4.118 出包核对时被这一点坑过一次）。
FIXED = (1981, 1, 1, 1, 1, 0)
def info(name):
    zi = zipfile.ZipInfo(name, date_time=FIXED)
    zi.compress_type = zipfile.ZIP_STORED
    zi.external_attr = 0o644 << 16
    return zi
with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as z:
    z.writestr(info("classes.dex"), src)
    z.writestr(info("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\r\n\r\n")
print("[helper] apk ->", dst, dst.stat().st_size, "B")
PYEOF2
echo "[helper] -> app/src/main/assets/ls-helper.dex ($(stat -f%z app/src/main/assets/ls-helper.dex) B)"
