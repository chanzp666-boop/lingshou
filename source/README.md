# 灵手 · 公开版源码

Android 10 车机应用：主屏全屏手势 + 仪表屏自绘卡片（油耗/胎压/音乐歌词）+ 三指空调。
公开版包名 `com.byd.ls`，使用独立 release 签名。

## 版本

- `versionCode 2697` / `versionName 1.5.4.6`
- 远程调试固定关闭：公开构建下 `RemoteConfig` 恒返回关闭，不读取外部 relay 地址；远程 Agent 组件不可外部拉起。
- release 包不包含 `com.byd.ls.action.RPC` 导出广播；`RestartReceiver` 只保留看门狗需要的重启/自愈动作。
- 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，保持与历史公开版一致。
- 公开源码中 ADB 命令、无障碍组件、仪表卡片、通知监听、守护进程均按 `com.byd.ls` 解析。

## 1.5.4.6 功能同步

- 同步自用最新 1.5.4.6 的功能与性能：`FeatureGate` 全关门控、导航 `amapsig` 早信号、小窗歌词静态帧停绘、QQ/酷狗歌词候选回退。
- 全关后停止对应卡片窗口、监听、轮询、线程、网络与歌词请求；重开后恢复。跨进程卡片 flag 继续供 `:guard` 与 shell 看门狗使用。
- 歌词主源先行 1.8s，超时或未命中再启动三源对冲；总预算 9s；只有四源都干净返回“没有这首歌”才写 24h 负缓存。
- 导航优先使用 helper 从 `AmapService` 日志提取的 `mIsBYDMapNaving` 早信号；信号失效才进入像素兜底。
- 小窗歌词行号/文本未变化时不再重绘静态层；QQ/酷狗按同名且时长差 ≤1.5s 回退候选，修复《壁上观》等被首选无词版本误杀的问题。
- **公开专属边界未改**：ADB 手动授权走 `connectForAuthorization`（`abortOnUnauthorised=false`）；胎压由 `TyreSource` 优先迪加 `127.0.0.1:8988`、5s 无完整四轮才回退原生映射；远程调试固定关闭，无 `ACTION_RPC`。
- 验证：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**160 tests / 0 failures / 0 errors / 2 skipped**；`versionCode=2697`、`versionName=1.5.4.6`。

## 1.5.3 三指空调与深浅跟随


- 空调控制由双指改为三指：左右滑=风量，上下滑=温度；1/2/4 指不参与，2 指恢复透传。
- `finger-touch` 只在三指时截断物理触摸并发质心采样；`AcFeature` 只在 `C,3` 亮 HUD，`GestureEngine` 只接受 3 指。
- 主界面与仪表卡片跟随车机系统 `uiMode` 切换深/浅色；空调 HUD 固定 DiLink 5 原厂深灰。
- `AcFeature` 增加对象锁 + helper `flock` 单实例锁，避免 adblib 超时重试拉起多个 `ls-finger-touch`。
- 验证：`assembleRelease` **BUILD SUCCESSFUL**；手势单测通过；`versionCode=2696`、`versionName=1.5.3`。

## 1.5.2 全关即停修复（历史）

- 卡片总开关新增跨进程 flag：私有 `filesDir/ls_card_any.flag`，公共 `/sdcard/clusterlab_card_any.txt`；`:guard` 与 shell 看门狗都读它。
- 全关后 `ClusterCardKeeper.heal()` 直接跳过；shell 看门狗 `ensure_card()` 在 `flag=0` 时不再 `am start-activity`，不再反复拉起空 `CardActivity`。
- 卡片总开关与 5 个元素开关改为 `commit()` 同步落盘；进程切开关后被杀也不会把全关回滚成开。
- 重开任一卡片功能：flag 变 1，`:guard` 与看门狗恢复卡片。
- 构建验证：`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；159 tests / 0 failures / 0 errors / 2 skipped。

## 1.5.1.3 胎压优先迪加

- 四轮优先读迪加本地 HTTP `127.0.0.1:8988/api/getVal`；启动后给 5 秒完整四轮返回窗口，四轮全部有效即锁定为唯一数据源。
- 锁定后原生 FID/instrument 胎压映射不再写入四轮；迪加临时失败保留最后一次迪加值，不回退映射。
- 5 秒内没有完整四轮才允许原生映射写入；迪加稍后返回完整四轮会立即切回并锁定。半套数据不锁定。
- 4 轮并发、单条 800ms 超时、2 秒节拍；兼容 kPa 与 bar（2.45 → 245 kPa）。
- Manifest 已有 `INTERNET` 与 `usesCleartextTraffic="true"`，无新增权限。

## 1.5.1.2 胎压降级

- 原生 instrument 四轮不全/异常时，从迪加本地 HTTP `127.0.0.1:8988/api/getVal` 补缺失轮位；四轮原生都有效时不请求迪加。
- 4 轮并发，成功 5s 刷新、失败 15s 退避；迪加未装、8988 未开、超时或返回失败时静默保持原生值。
- Manifest 已有 `INTERNET` 与 `usesCleartextTraffic="true"`，无新增权限。

## 1.5.1.1 修复

- 手动「一键启动 / 修复」改走 `AdbLibChannel.connectForAuthorization()`：adblib 以 `abortOnUnauthorised=false` 在第二次 AUTH 发送 `AUTH_RSAPUBLICKEY`，车机才会弹「允许 USB 调试吗」。
- 授权连接保持到用户点「允许」或 60s 超时；成功后可拿 uid 2000 shell。
- 开机静默探测去掉未写入的 `adb_trusted` 门槛：已信任 key 可自动连接；未信任时静默失败，不弹 RSA。
- `AUTO_AUTH_ENABLED` 保持关闭，避免重复弹窗。

## 1.5.1 修复

- 仪表媒体恢复：启动后循环重绑通知监听，`ClusterCardKeeper` 的媒体修复节拍缩短到 2 分钟。
- 歌词冷启动：繁体歌名归一化为简体再搜，清掉旧版误写的 `.lrc.miss` 负缓存。
- 空调 HUD：固定原厂深灰，不跟随系统日/夜模式。

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
helper-native/    finger-touch 源码与构建说明
tools/            构建脚本
```

## 边界

- 不自带 relay/operator/bootstrap 配置；
- 不包含内部交接文档、设备 ID、远程运维工具、keystore 或口令；
- 旧版本、旧 tag、旧 Release 与旧 APK 不在本仓库保留。

## 许可协议

本源码快照采用 **PolyForm Noncommercial License 1.0.0**，仅允许非商业用途；禁止销售、付费分发、商业产品/服务集成。商业使用需另行取得作者书面授权。完整条款见仓库根目录 [LICENSE](../LICENSE)。

