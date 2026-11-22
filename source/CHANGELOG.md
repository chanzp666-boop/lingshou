## 1.5.1.2（versionCode 2693，2026-11-22）—— 公开版胎压降级

- **版本**：`versionCode 2693` / `versionName 1.5.1.2`；包名 `com.byd.ls`，仍使用独立公开 release 签名。
- **胎压降级**：原生 instrument 四轮不全/异常（含 4094）时，从迪加本地 HTTP `127.0.0.1:8988/api/getVal` 读取 `左前轮气压`、`右前轮气压`、`左后轮气压`、`右后轮气压`，只补缺失轮位。
- **不覆盖原生**：四轮原生值都有效时不发请求；迪加未装、8988 未开、超时、失败都静默回原生。
- **网络**：4 轮并发，连接/读取超时各 1.5s；成功 5s 刷新，失败 15s 退避，避免每 800ms 重复打 4 次。
- **边界**：仅公开仓改动；自用版 1.5.1 未动。`AndroidManifest` 已有 `INTERNET` 与 `usesCleartextTraffic="true"`，不需要新增权限或网络安全配置。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- **产物**：`apk/lingshou-1.5.1.2.apk`，**1,528,727 B**，sha256 `74bdc7bc47e639c66b71a00e2148a2c50e739b2e277a14d50edec98623be5765`。

## 1.5.1.1（versionCode 2692，2026-11-22）—— ADB 授权修复

- **版本**：`versionCode 2692` / `versionName 1.5.1.1`；包名 `com.byd.ls`，仍使用独立公开 release 签名。
- **修复**：手动「一键启动 / 修复」改走 `AdbLibChannel.connectForAuthorization()`，adblib `abortOnUnauthorised=false`，第二次 AUTH 发送 `AUTH_RSAPUBLICKEY`；此前 abort=true 会在发公钥前抛异常，车机不弹「允许 USB 调试吗」。
- **授权窗口**：连接保持到用户点「允许」或 60s 超时；成功后可用 uid 2000 shell。
- **开机**：静默探测去掉从未写入的 `adb_trusted` 门槛；已信任 key 可自动连接，未信任时只静默失败，不弹 RSA。
- **边界**：`AUTO_AUTH_ENABLED` 仍为 false，避免开机/心跳重复弹窗；本次只改公开仓，自用版 1.5.1 未动。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- **产物**：`apk/lingshou-1.5.1.1.apk`，**1,528,727 B**，sha256 `99046a10c92d7fe24682d7ee18c4b93f1ec44f07a632ab403bd787c0ea22d23b`。

## 1.5.1（versionCode 2691，2026-11-21）—— 公开正式版

- **版本**：`versionCode 2691` / `versionName 1.5.1`；包名 `com.byd.ls`，与自用版同包名但使用独立 release 签名。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- **产物**：`apk/lingshou-1.5.1.apk`，**1,528,727 B**，sha256 `1cc6e0156ada88406a97d95fd336e5e9cb8ca12fa0af5967d7390b1b5624db42`。
- **修复**：休眠/重启后主动重绑通知监听，媒体修复节拍缩到 2 分钟；繁体歌名转简体搜索并清一次旧负缓存；双指空调 HUD 固定原厂深灰。
- **硬件/远程边界**：公开包远程调试固定关闭；不包含 `com.byd.ls.action.RPC`；远程 Agent 组件不可外部拉起。
- **权限**：保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；移除 `ACTION_RPC`。
- **安装代价**：公开版与自用版签名不同，不能覆盖安装或共存；安装前必须卸载旧自用版，应用数据会丢失。
- **仓库**：旧 tag、旧 Release、旧 APK 已清理；只保留当前公开版。

## 1.5-public（versionCode 2690，2026-11-21）—— 首次公开版

- 首次公开构建，独立 release 证书；恢复保活权限，移除 `ACTION_RPC`，远程调试固定关闭。
- 清理旧历史、旧 tag、旧 Release、旧 APK 与内部文档。

> 本公开仓只保留当前 1.5.1.1；自用包、内部文档和远程运维工具不在此仓库。
