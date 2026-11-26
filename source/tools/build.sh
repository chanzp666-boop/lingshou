#!/usr/bin/env bash
# 灵手 · 构建入口。
#
# 作用：自动定位 JDK 17 与 Android SDK，准备好 Robolectric 目录，必要时生成 SDK shim
# （AGP 8.1 的 lint 解析不了 37.2 这种带小数的 AndroidVersion.ApiLevel，会让 lintAnalyzeDebug 直接崩），
# 然后把参数原样透传给 ./gradlew。不带参数时跑「单测 + lint + debug 包」。
#
#   tools/build.sh testDebugUnitTest lintDebug assembleRelease
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
SHIM="$ROOT/.sdk-shim"

die() { echo "错误：$*" >&2; exit 1; }

# ---------- JDK 17 ----------
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17'; then
  JAVA_HOME=""
  for c in "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
           /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
           /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    [ -n "$c" ] && [ -x "$c/bin/javac" ] || continue
    if "$c/bin/java" -version 2>&1 | grep -q '"17'; then JAVA_HOME="$c"; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] || die "找不到 JDK 17（macOS: brew install openjdk@17）"
export JAVA_HOME

# ---------- Android SDK ----------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  cand="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"
  # 工程被搬动后 local.properties 里可能还留着上一个位置的 .sdk-shim 路径，不能当真实 SDK 用
  case "$cand" in ""|*"/.sdk-shim") ;; *) SDK="$cand" ;; esac
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || SDK="$HOME/Library/Android/sdk"
[ -d "$SDK/platforms" ] || die "Android SDK 不可用：$SDK（设置 ANDROID_SDK_ROOT 或 local.properties）"

COMPILE_SDK="$(sed -n 's/^ *compileSdk *\([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)"
[ -n "$COMPILE_SDK" ] || COMPILE_SDK=34

# ---------- 只在必要时生成 SDK shim ----------
needs_shim=false
for p in "$SDK"/platforms/*/source.properties; do
  [ -f "$p" ] || continue
  api="$(sed -n 's/^AndroidVersion\.ApiLevel=//p' "$p" | tail -1)"
  case "$api" in *.*) needs_shim=true ;; esac
done

if [ "$needs_shim" = true ]; then
  PLATFORM="$SDK/platforms/android-$COMPILE_SDK"
  [ -d "$PLATFORM" ] || die "缺少 platforms/android-$COMPILE_SDK（compileSdk=$COMPILE_SDK）"
  rm -rf "$SHIM"
  mkdir -p "$SHIM/platforms" "$SHIM/build-tools"
  ln -s "$PLATFORM" "$SHIM/platforms/android-$COMPILE_SDK"
  for d in "$SDK"/build-tools/*; do
    [ -d "$d" ] || continue
    ln -s "$d" "$SHIM/build-tools/$(basename "$d")"
  done
  for name in ndk cmake platform-tools licenses tools; do
    if [ -d "$SDK/$name" ]; then ln -s "$SDK/$name" "$SHIM/$name"; fi
  done
  SDK_DIR="$SHIM"
  echo "[build] SDK 含带小数的 API 平台，已生成 .sdk-shim 绕开 AGP 8.1 lint 解析缺陷"
else
  SDK_DIR="$SDK"
fi

# sdk.dir 变化会让 app/.cxx 里缓存的 CMake/ninja 绝对路径失效（换 shim 时必踩），先清掉。
PREV_SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | tail -1 || true)"
if [ -n "$PREV_SDK_DIR" ] && [ "$PREV_SDK_DIR" != "$SDK_DIR" ]; then
  echo "[build] sdk.dir 变化，清理 native 配置缓存 app/.cxx"
  rm -rf app/.cxx
fi
printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
mkdir -p "$ROOT/build/robolectric-home"

# App 编译期要用 BYD framework 的签名（App 内 extends AbsBYDAuto*Listener），先出 stub jar
# 公开源码快照不含 helper/stub，直接使用 app/libs 里随仓库发布的公开 stub，避免空 jar 覆盖。
if [ -d helper/stub ] && [ -f tools/build-framework-stub.sh ]; then ./tools/build-framework-stub.sh || true; fi

# helper 的 dex/apk 是构建产物（.gitignore 已忽略），换机器、刚 clone、或改了 helper/src 时必须重编；
# 漏编的后果不是报错，而是 APK 里带着**旧 fid 表**（实测：输出 129 行而不是 131，新 fid 全读不到）。
HELPER_APK=app/src/main/assets/ls-helper.apk
if [ ! -f "$HELPER_APK" ] || [ -n "$(find helper/src helper/stub -name '*.java' -newer "$HELPER_APK" -print -quit 2>/dev/null)" ]; then
  echo "[build] helper 需重编（$HELPER_APK 缺失或比源码旧）"
  ./tools/build-helper.sh || die "helper 编译失败"
else
  echo "[build] helper 已是最新：$HELPER_APK $(stat -f%z "$HELPER_APK") B"
fi

ARGS=("$@")
[ ${#ARGS[@]} -gt 0 ] || ARGS=(testDebugUnitTest lintDebug assembleDebug)
echo "[build] JAVA_HOME=$JAVA_HOME"
echo "[build] sdk.dir=$SDK_DIR"
exec ./gradlew --console=plain "${ARGS[@]}"
