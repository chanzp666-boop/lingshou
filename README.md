# 灵手 · 灵手仓库（公开版）

DiLink 4.0（Android 10）车机应用：主屏全屏手势 + 仪表屏自绘卡片（油耗/胎压/歌词）+ 双指空调控制。
单 APK，不改系统 App，不依赖 root/Shizuku；特权操作走内置 adblib → `127.0.0.1:5555` 的 uid 2000 shell。

这是公开专用仓库，**只保留当前公开版**：

- 公开包：`com.byd.ls` / `versionCode 2693` / `versionName 1.5.1.2`
- 公开版使用独立 release 证书签名，不能覆盖自用版，也不能与自用版共存

> **安装前必须卸载旧的 `com.byd.ls`（自用版）**。公开版与自用版签名不同，Android 会拒绝覆盖安装；卸载会清除应用数据。已装公开版 1.5.1 / 1.5.1.1 的可以直接覆盖升级到 1.5.1.2。
>
> 如果车机上装过旧的 `com.byd.ls.public` 公开版，也要一并卸载；它是另一个包名，不会被新包覆盖。

## 下载

- `apk/lingshou-1.5.1.2.apk` — 1.5.1.2 公开版
- `apk/SHA256SUMS.txt` — APK 校验值

| 项 | 值 |
|---|---|
| 包名 | `com.byd.ls` |
| versionCode / versionName | `2693` / `1.5.1.2` |
| 大小 | 1,528,727 B |
| SHA-256 | `74bdc7bc47e639c66b71a00e2148a2c50e739b2e277a14d50edec98623be5765` |
| 签名 | 独立公开 release 证书，证书 SHA-256 `3920e540903d9be162d43c534c5cb8d0fc7c611c07d5036fd6e0176661780843` |
| 远程调试 | 固定关闭，不包含 relay 地址、bootstrap、operator token |

## 1.5.1.2 胎压降级

- 其他 BYD 车型的原生 instrument 四轮 ID 与本车不一致、读数缺失或异常时，自动从迪加本地 HTTP `127.0.0.1:8988` 的 `/api/getVal` 补缺失轮位。
- 原生四轮都有效时不请求迪加；迪加只补缺，不覆盖正常原生值。
- 迪加未安装、8988 未启动、超时或返回失败时静默保持原生值，不影响启动和现有功能。
- 仅改公开仓库；自用版 1.5.1（versionCode 272）未改动。
- 仍是同一公开 release 签名，已装 1.5.1 / 1.5.1.1 可直接覆盖升级。

## 1.5.1.1 修复

- 修复新车机上「ADB 握手失败、车机不弹允许框」：手动「一键启动 / 修复」改走 adblib 的授权模式（`abortOnUnauthorised=false`），真正发送 `AUTH_RSAPUBLICKEY`，adbd 才会显示「允许 USB 调试吗」。
- 授权成功后即可拿到 uid 2000 shell；首次使用必须点一次「一键启动 / 修复」并在车机屏幕上点「允许」。
- 开机静默探测不再依赖从未写入的 `adb_trusted` 标记：已信任 key 的车机可自动连上；未信任时只静默失败，不会弹 RSA。
- 自动授权开关仍保持关闭，避免历史上的弹窗风暴。

## 1.5.1 修复

- 仪表媒体恢复：车机休眠/重启后主动重绑通知监听，缩短媒体修复节拍，封面与歌词不再长时间空白。
- 歌词冷启动：繁体歌名按简体候选重试，清掉旧版误写的歌词负缓存，Apple Music 等繁体曲名也能命中。
- 双指空调 HUD：固定原厂深灰配色，不再跟随系统日/夜模式变色。

## 功能与权限

- 公开版与自用版同源，手势、仪表卡片、媒体、空调、无障碍、看门狗逻辑一致；1.5.1.1 的 ADB 授权修复与 1.5.1.2 的胎压降级只进入公开版，自用版 1.5.1 未改动。
- 远程调试固定关闭：`RemoteConfig` 在公开构建中恒返回关闭，不读取外部 relay 地址；远程 Agent 组件不可外部拉起。
- 已移除 `com.byd.ls.action.RPC` 广播入口，不能通过广播调用 `RemoteRpc`。
- 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，与自用版保持一致，减少 OEM 后台策略差异。
- 一键 ADB 授权要求用户打开车机 ADB、确认 RSA；`WRITE_SECURE_SETTINGS`、`DUMP` 需要 ADB 授权后才能使用。
- 公开包不含 relay 配置、设备 ID、bootstrap/operator/ngrok 凭据、签名私钥或口令。

## 源码

`source/` 是公开版源码快照，已移除内部文档、远程运维工具和私有配置。构建要求：

```bash
cd source
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export LS_PUBLIC_STORE_FILE=/path/to/lingshou-public-release.jks
export LS_PUBLIC_STORE_PASSWORD=...
export LS_PUBLIC_KEY_ALIAS=lingshou-public
export LS_PUBLIC_KEY_PASSWORD=...
./tools/build.sh clean testReleaseUnitTest assembleRelease
```

公开源码需要自己的 release keystore；仓库不提供私钥。构建产物为
`app/build/outputs/apk/release/app-release.apk`。

## 目录

```text
apk/        公开包与 SHA256
source/     公开版 Gradle 工程
CHANGELOG.md
```

自用车机包、内部交接文档、远程 relay 工具不在此仓库。
