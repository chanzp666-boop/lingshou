# finger-touch（arm64 native helper）

抓取 `/dev/input/event0`，1/2 指原样转发到 uinput，3 指截断物理触摸并向 App 发送 `C,3` / `S,cx,cy`；App 侧由 `GestureEngine` 驱动三指空调。

构建（NDK 25.1.8937393）：

```bash
NDK="$HOME/Library/Android/sdk/ndk/25.1.8937393"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang"
STRIP="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
"$CC" -O2 -fPIE -pie -Wall -Wextra -Wno-unused-result \
  -o /tmp/finger-touch finger_touch.c
"$STRIP" --strip-all /tmp/finger-touch
cp /tmp/finger-touch ../app/src/main/assets/finger-touch
chmod 755 ../app/src/main/assets/finger-touch
```

产物是裸 ELF，不带签名；由 App 启动时推送到 `/data/local/tmp/ls-finger-touch` 并 `chmod 755`。修改后必须重跑 `tools/build.sh testReleaseUnitTest assembleRelease`，确认包内 `assets/finger-touch` SHA 与本地一致。
