# 灵手 · 灵手仓库（公开版）

> **非商业许可**：本仓库采用 **PolyForm Noncommercial License 1.0.0**，仅允许个人学习、研究、测试等非商业用途；禁止销售、付费分发、商业产品/服务集成。商业使用需另行取得作者书面授权。完整条款见 [LICENSE](LICENSE)。

DiLink 4.0（Android 10）车机应用：主屏全屏手势 + 仪表屏自绘卡片（油耗/胎压/歌词）+ 三指空调控制。
单 APK，不改系统 App，不依赖 root/Shizuku；特权操作走内置 adblib → `127.0.0.1:5555` 的 uid 2000 shell。

这是公开专用仓库，**只保留当前公开版**：

- 公开包：`com.byd.ls` / `versionCode 2696` / `versionName 1.5.3`
- 公开版使用独立 release 证书签名

> 首次安装直接安装 APK；已装 1.5.2 可直接升级到 1.5.3。

## 下载

- `apk/lingshou-1.5.3.apk` — 1.5.3 公开版
- `apk/SHA256SUMS.txt` — APK 校验值

| 项 | 值 |
|---|---|
| 包名 | `com.byd.ls` |
| versionCode / versionName | `2696` / `1.5.3` |
| 大小 | 1,532,943 B |
| SHA-256 | `ff50272f29613564373e08be77a6bb464ca0b7e398feb8c20f4a36052356ba85` |
| 签名 | 独立公开 release 证书，证书 SHA-256 `3920e540903d9be162d43c534c5cb8d0fc7c611c07d5036fd6e0176661780843` |
| 远程调试 | 固定关闭，不包含 relay 地址、bootstrap、operator token |

## 1.5.3 三指空调与深浅跟随

- 空调控制由双指改为三指：三指左右滑=风量，上下滑=温度；1/2/4 指不参与，2 指恢复透传，地图/图片双指缩放不再被空调截断。
- `finger-touch` 只在三指时截断物理触摸并发质心采样；`AcFeature` 只在 `C,3` 亮 HUD，`GestureEngine` 只接受 3 指。
- 主界面与仪表卡片继续跟随车机系统 `uiMode` 切换深/浅色；空调 HUD 固定 DiLink 5 原厂深灰，不随深浅模式变色。
- `AcFeature` 增加对象锁 + helper `flock` 单实例锁，避免 adblib 超时重试拉起两个 `ls-finger-touch`。
- 同一公开 release 签名，已装 1.5.2 可直接升级。
- 构建验证：JDK 17，`assembleRelease` **BUILD SUCCESSFUL**；手势单测通过；APK `versionCode=2696`、`versionName=1.5.3`。

## 1.5.2 全关即停修复（历史）

- 卡片总开关新增跨进程 flag：私有 `filesDir/ls_card_any.flag`，公共 `/sdcard/clusterlab_card_any.txt`；`:guard` 与 shell 看门狗都读它，不再依赖各自进程里的旧 SP 缓存。
- 全关后 `ClusterCardKeeper.heal()` 直接跳过；shell 看门狗 `ensure_card()` 在 `flag=0` 时不再执行 `am start-activity`，不再每 15s 反复拉起空 `CardActivity`。
- 卡片总开关与 5 个元素开关改为 `commit()` 同步落盘；切完全关后进程被杀，重启也不会把“全关”回滚成“开”。
- 重开任一卡片功能：flag 变 1，`:guard` 与看门狗恢复卡片；全关保持无 `CardActivity` 窗口。
- 同一公开 release 签名，已装 1.5.1.3 可直接升级。
- 构建验证：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**159 tests / 0 failures / 0 errors / 2 skipped**（新增 `CardFlagDiskTest` 5 项）。

## 1.5.1.3 胎压优先迪加

- 四轮胎压优先从迪加本地 HTTP `127.0.0.1:8988` 的 `/api/getVal` 读取；启动后给迪加 5 秒完整四轮返回窗口。
- 迪加四轮全部有效即锁定为唯一数据源；此后原生 FID/instrument 的胎压映射不再写入四轮。迪加临时失败保留最后一次迪加值，不回退映射。
- 5 秒内迪加没有返回完整四轮值时，才允许原生映射表写入；迪加稍后返回完整四轮会立即切回并锁定。
- 迪加只返回 1–3 轮时不锁定，避免另外三轮长期空着；单位兼容 kPa，返回 bar 时自动 ×100。
- 全程只走本机回环，不增加公网流量；迪加未安装/8988 未启动时静默走原生映射，不影响启动。
- 同一公开 release 签名，已装 1.5.1.2 可直接升级。

## 1.5.1.2 胎压降级

- 其他 BYD 车型的原生 instrument 四轮 ID 与本车不一致、读数缺失或异常时，自动从迪加本地 HTTP `127.0.0.1:8988` 的 `/api/getVal` 补缺失轮位。
- 原生四轮都有效时不请求迪加；迪加只补缺，不覆盖正常原生值。
- 迪加未安装、8988 未启动、超时或返回失败时静默保持原生值，不影响启动和现有功能。
- 同一公开 release 签名，已装 1.5.1 / 1.5.1.1 可直接升级。

## 1.5.1.1 修复

- 修复新车机上「ADB 握手失败、车机不弹允许框」：手动「一键启动 / 修复」改走 adblib 的授权模式（`abortOnUnauthorised=false`），真正发送 `AUTH_RSAPUBLICKEY`，adbd 才会显示「允许 USB 调试吗」。
- 授权成功后即可拿到 uid 2000 shell；首次使用必须点一次「一键启动 / 修复」并在车机屏幕上点「允许」。
- 开机静默探测不再依赖从未写入的 `adb_trusted` 标记：已信任 key 的车机可自动连上；未信任时只静默失败，不会弹 RSA。
- 自动授权开关仍保持关闭，避免历史上的弹窗风暴。

## 1.5.1 修复

- 仪表媒体恢复：车机休眠/重启后主动重绑通知监听，缩短媒体修复节拍，封面与歌词不再长时间空白。
- 歌词冷启动：繁体歌名按简体候选重试，清掉旧版误写的歌词负缓存，Apple Music 等繁体曲名也能命中。
- 空调 HUD：固定原厂深灰配色，不再跟随系统日/夜模式变色。

## 功能与权限

- 公开版手势、仪表卡片、媒体、空调、无障碍、看门狗逻辑与历史公开版一致；1.5.1.1 的 ADB 授权修复、1.5.1.2 的胎压降级、1.5.1.3 的胎压优先迪加、1.5.2 的全关即停修复、1.5.3 的三指空调与 helper 单实例锁均已包含在当前包。
- 远程调试固定关闭：`RemoteConfig` 在公开构建中恒返回关闭，不读取外部 relay 地址；远程 Agent 组件不可外部拉起。
- 已移除 `com.byd.ls.action.RPC` 广播入口，不能通过广播调用 `RemoteRpc`。
- 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，保持与历史公开版一致，减少 OEM 后台策略差异。
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

内部交接文档、远程 relay 工具不在此仓库。

## 许可协议

本项目采用 **PolyForm Noncommercial License 1.0.0**。这是 **source-available** 许可，不是 OSI 定义的开源许可。

- 允许：个人学习、研究、实验、测试、业余项目等非商业用途；非商业前提下可修改和分享。
- 禁止：销售、付费分发、商业产品/服务集成、企业商业运营等任何商业用途。
- 商业使用需另行取得作者书面授权。
- 分享或修改时需保留本许可证及 `Required Notice`。
- 第三方依赖、AndroidX、adblib 等仍按各自许可证使用。

完整条款以 [LICENSE](LICENSE) 为准。

