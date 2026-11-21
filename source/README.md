# 灵手 · 公开版源码

Android 10 车机应用：主屏全屏手势 + 仪表屏自绘卡片（油耗/胎压/音乐歌词）+ 双指空调。
公开版包名 `com.byd.ls`，与自用版同包名，但使用独立 release 签名，不能共存。

## 版本

- `versionCode 2692` / `versionName 1.5.1.1`
- 远程调试固定关闭：公开构建下 `RemoteConfig` 恒返回关闭，不读取外部 relay 地址；远程 Agent 组件不可外部拉起。
- release 包不包含 `com.byd.ls.action.RPC` 导出广播；`RestartReceiver` 只保留看门狗需要的重启/自愈动作。
- 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，与自用版保持一致。
- 公开源码中 ADB 命令、无障碍组件、仪表卡片、通知监听、守护进程均按 `com.byd.ls` 解析。

## 1.5.1.1 修复

- 手动「一键启动 / 修复」改走 `AdbLibChannel.connectForAuthorization()`：adblib 以 `abortOnUnauthorised=false` 在第二次 AUTH 发送 `AUTH_RSAPUBLICKEY`，车机才会弹「允许 USB 调试吗」。
- 授权连接保持到用户点「允许」或 60s 超时；成功后可拿 uid 2000 shell。
- 开机静默探测去掉未写入的 `adb_trusted` 门槛：已信任 key 可自动连接；未信任时静默失败，不弹 RSA。
- `AUTO_AUTH_ENABLED` 保持关闭，避免重复弹窗。

## 1.5.1 修复

- 仪表媒体恢复：启动后循环重绑通知监听，`ClusterCardKeeper` 的媒体修复节拍缩短到 2 分钟。
- 歌词冷启动：繁体歌名归一化为简体再搜，清掉旧版误写的 `.lrc.miss` 负缓存。
- 双指空调 HUD：固定原厂深灰，不跟随系统日/夜模式。

## 构建

要求 JDK 17（不要用 JDK 21 直接跑 AGP 8.1.4）：

```bash
cd source
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export LS_PUBLIC_STORE_FILE=/path/to/lingshou-public-release.jks
export LS_PUBLIC_STORE_PASSWORD=...
export LS_PUBLIC_KEY_ALIAS=lingshou-public
export LS_PUBLIC_KEY_PASSWORD=...
./tools/build.sh clean testReleaseUnitTest assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

公开源码不提供签名私钥；release 构建必须由发布环境注入 keystore。仓库中的
`app/src/main/assets/ls-helper.apk` 与 `finger-touch` 是运行时资源，不是签名材料。

## 目录

```text
app/src/main/java/com/byd/ls/
├── config/       GesturePrefs
├── service/      手势、边缘层、无障碍自愈、:guard 守护、卡片巡检
├── shell/        adblib 通道、密钥、一键启动、看门狗安装、BydDirect
├── receiver/     BootReceiver / RestartReceiver / HeartbeatReceiver
├── guard/        native 双进程守护
├── ui/           主界面
├── cluster/      仪表卡片
└── util/         LsLog
app/src/main/cpp/ lsguard.cpp
assets/           car_top.webp、ls-watchdog.sh、ls-helper.apk、finger-touch
tools/            构建脚本
```

## 边界

- 不自带 relay/operator/bootstrap 配置；
- 不包含内部交接文档、设备 ID、远程运维工具、keystore 或口令；
- 旧版本、旧 tag、旧 Release 与旧 APK 不在本仓库保留。
