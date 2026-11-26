## 1.5.4.6（versionCode 2697，2026-11-26）—— 同步自用 1.5.4.6 功能

- **版本**：`versionCode 2697` / `versionName 1.5.4.6`；公开 code 2697 保证 1.5.3 / 2696 可覆盖升级，显示版本与自用最新版一致。
- **全关门控**：新增 `FeatureGate`；卡片/油耗/胎压/音乐关闭后停止对应窗口、监听、轮询、线程、网络与歌词请求；重开后恢复。
- **导航早信号**：helper 从 `AmapService` 日志提取 `mIsBYDMapNaving`，`NavState` 优先按 `/data/local/tmp/.nav_sig` 切换；信号失效才进入像素兜底；移除旧 `NavReceiver`。
- **小窗歌词**：行号与文本未变化时不再重绘静态层，减少静止帧 CPU 与整层重建。
- **歌词候选回退**：QQ/酷狗首选候选无词时，按同名且时长差 ≤1.5s 继续尝试其他候选，修复《壁上观》等误杀。
- **歌词策略**：主源先行 1.8s，超时/未命中再启动三源对冲；总预算 9s；只有四源都干净返回“没有这首歌”才写 24h 负缓存。
- **公开边界**：ADB 手动授权仍走 `connectForAuthorization`（`abortOnUnauthorised=false`）；胎压仍由 `TyreSource` 优先迪加 `127.0.0.1:8988`、5s 无完整四轮才回退原生映射；远程调试固定关闭，无 `ACTION_RPC`。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**160 tests / 0 failures / 0 errors / 2 skipped**；APK 1,545,771 B，sha256 `a780f52120d6210cde7f2a0189000f6bf214aebfc3c1973e79ab4c6769385dcc`。
- **产物**：`apk/lingshou-1.5.4.6.apk`；历史 `v1.5.2`、`v1.5.3` 保留。

## 1.5.3（versionCode 2696，2026-11-23）—— 三指空调

- **版本**：`versionCode 2696` / `versionName 1.5.3`；包名 `com.byd.ls`，使用独立公开 release 签名。
- **手势**：空调控制由双指改为三指，三指左右滑=风量、上下滑=温度；1/2/4 指不参与，2 指恢复透传。
- **原生助手**：`finger-touch` 只在 `count >= 3` 时截断物理触摸并发送质心采样；源码见 `helper-native/`，包内资源 SHA 为 `22723deb…9abe`。
- **应用层**：`AcFeature` 只在 `C,3` 亮 HUD；`GestureEngine.onSample` 只接受 3 指；设置页文案改「三指空调控制」。
- **单实例**：`AcFeature` 的对象锁 + helper `flock` 锁，避免 adblib 超时重试拉起两个 `ls-finger-touch`。
- **深浅**：主界面与仪表卡片跟随系统 `uiMode` 切深/浅；空调 HUD 固定 DiLink 5 原厂深灰。
- **构建/验证**：JDK 17，`assembleRelease` **BUILD SUCCESSFUL**；手势单测 17/17 通过；`versionCode=2696`、`versionName=1.5.3`。
- **产物**：`apk/lingshou-1.5.3.apk`，**1,532,943 B**，sha256 `ff50272f29613564373e08be77a6bb464ca0b7e398feb8c20f4a36052356ba85`。

## 1.5.2（versionCode 2695，2026-11-23）—— 公开版全关即停修复

- **版本**：`versionCode 2695` / `versionName 1.5.2`；包名 `com.byd.ls`，仍使用独立公开 release 签名。
- **跨进程卡片 flag**：`GesturePrefs` 增加私有 `ls_card_any.flag` 与公共 `/sdcard/clusterlab_card_any.txt`；`:guard` 读 `cardAnyFromDisk()`，不再依赖旧 SP 快照。
- **全关巡检**：`ClusterCardKeeper.heal()` 读 flag=0 直接跳过；shell `ls-watchdog.sh` 的 `ensure_card()` 同样在 `flag=0` 时返回，不再每 15s `am start-activity` 拉空 `CardActivity`。
- **同步落盘**：卡片总开关与 5 个元素开关由 `apply()` 改 `commit()`；切完全关后进程被杀，重启不会把全关回滚成开。
- **恢复**：重开任一卡片功能写 flag=1，`:guard` 与看门狗恢复卡片；全关保持无 CardActivity 窗口。
- **边界**：远程调试仍固定关闭，无 `ACTION_RPC`。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**159 tests / 0 failures / 0 errors / 2 skipped**；新增 `CardFlagDiskTest` 5 项。
- **产物**：`apk/lingshou-1.5.2.apk`，**1,532,963 B**，sha256 `3420d2facf48ae6f5b209e39840486df811f233f888a959e5f246c7c4eb2fa70`。

## 1.5.1.3（versionCode 2694，2026-11-23）—— 公开版胎压优先迪加

- **版本**：`versionCode 2694` / `versionName 1.5.1.3`；包名 `com.byd.ls`，仍使用独立公开 release 签名。
- **胎压单一源**：新增 `TyreSource` 仲裁。迪加 `127.0.0.1:8988/api/getVal` 四轮并发读取，四轮全部有效即锁定；5 秒内无完整四轮才允许原生 FID/instrument 映射写入。
- **锁定后**：原生 `applyFidSnapshot`、instrument 事件、旧快照都不再写胎压；迪加临时失败保留最后一次迪加值，不回退映射。
- **冷启动**：不从 `last-values.txt` 恢复胎压，避免先亮旧映射错值；油耗/电耗恢复不变。
- **网络**：4 轮并发，连接/读取各 800ms，2 秒节拍；全部走本机回环，无公网流量。
- **边界**：半套数据不锁定；迪加未装/8988 未开/超时静默走原生映射。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**154 tests / 0 failures / 0 errors / 2 skipped**；新增 `TyreSourceTest` 2 项。
- **产物**：`apk/lingshou-1.5.1.3.apk`，**1,532,823 B**，sha256 `8c9fdac171a7bbb9bd379fb1c2bbcff30a43d8b4e7619685a359ffae4ce88f67`。

## 1.5.1.2（versionCode 2693，2026-11-22）—— 公开版胎压降级

- **版本**：`versionCode 2693` / `versionName 1.5.1.2`；包名 `com.byd.ls`，仍使用独立公开 release 签名。
- **胎压降级**：原生 instrument 四轮不全/异常（含 4094）时，从迪加本地 HTTP `127.0.0.1:8988/api/getVal` 读取 `左前轮气压`、`右前轮气压`、`左后轮气压`、`右后轮气压`，只补缺失轮位。
- **不覆盖原生**：四轮原生值都有效时不发请求；迪加未装、8988 未开、超时、失败都静默回原生。
- **网络**：4 轮并发，连接/读取超时各 1.5s；成功 5s 刷新，失败 15s 退避，避免每 800ms 重复打 4 次。
- **边界**：`AndroidManifest` 已有 `INTERNET` 与 `usesCleartextTraffic="true"`，不需要新增权限或网络安全配置。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- **产物**：`apk/lingshou-1.5.1.2.apk`，**1,532,827 B**，sha256 `74bdc7bc47e639c66b71a00e2148a2c50e739b2e277a14d50edec98623be5765`。

## 1.5.1.1（versionCode 2692，2026-11-22）—— ADB 授权修复

- **版本**：`versionCode 2692` / `versionName 1.5.1.1`；包名 `com.byd.ls`，仍使用独立公开 release 签名。
- **修复**：手动「一键启动 / 修复」改走 `AdbLibChannel.connectForAuthorization()`，adblib `abortOnUnauthorised=false`，第二次 AUTH 发送 `AUTH_RSAPUBLICKEY`；此前 abort=true 会在发公钥前抛异常，车机不弹「允许 USB 调试吗」。
- **授权窗口**：连接保持到用户点「允许」或 60s 超时；成功后可用 uid 2000 shell。
- **开机**：静默探测去掉从未写入的 `adb_trusted` 门槛；已信任 key 可自动连接，未信任时只静默失败，不弹 RSA。
- **边界**：`AUTO_AUTH_ENABLED` 仍为 false，避免开机/心跳重复弹窗。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- **产物**：`apk/lingshou-1.5.1.1.apk`，**1,528,727 B**，sha256 `99046a10c92d7fe24682d7ee18c4b93f1ec44f07a632ab403bd787c0ea22d23b`。

## 1.5.1（versionCode 2691，2026-11-21）—— 公开正式版

- **版本**：`versionCode 2691` / `versionName 1.5.1`；包名 `com.byd.ls`，使用独立 release 签名。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- **产物**：`apk/lingshou-1.5.1.apk`，**1,528,727 B**，sha256 `1cc6e0156ada88406a97d95fd336e5e9cb8ca12fa0af5967d7390b1b5624db42`。
- **修复**：休眠/重启后主动重绑通知监听，媒体修复节拍缩到 2 分钟；繁体歌名转简体搜索并清一次旧负缓存；双指空调 HUD 固定原厂深灰。
- **硬件/远程边界**：公开包远程调试固定关闭；不包含 `com.byd.ls.action.RPC`；远程 Agent 组件不可外部拉起。
- **权限**：保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；移除 `ACTION_RPC`。
- **安装**：同一公开签名可直接升级。
- **仓库**：旧 tag、旧 Release、旧 APK 已清理；只保留当前公开版。

## 1.5-public（versionCode 2690，2026-11-21）—— 首次公开版

- 首次公开构建，独立 release 证书；恢复保活权限，移除 `ACTION_RPC`，远程调试固定关闭。
- 清理旧历史、旧 tag、旧 Release、旧 APK 与内部文档。

> 本公开仓只保留当前公开版；内部文档和远程运维工具不在此仓库。
