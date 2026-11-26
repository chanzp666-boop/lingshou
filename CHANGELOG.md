# 变更记录

## 1.5.4.6（versionCode 2697，2026-11-26）

- **版本**：`versionCode 2697` / `versionName 1.5.4.6`；包名 `com.byd.ls`，使用独立公开 release 签名。公开 code 2697 保证已装 1.5.3 / 2696 可直接覆盖升级；显示版本与自用最新版一致。
- **功能同步**：将 1.5.3beta1/beta2 至 1.5.4.6 的公开可同步改动合入公开源码，包括完整全关门控、导航早信号、小窗歌词静态帧停绘、歌词候选回退。
- **全关门控**：新增 `FeatureGate`；卡片/油耗/胎压/音乐关闭后停止对应窗口、监听、轮询、线程、网络与歌词请求；重开后恢复。跨进程卡片 flag 继续供 `:guard` 与 shell 看门狗使用。
- **歌词策略**：主源网易云先行 1.8s，超时或未命中再启动 QQ/酷狗/酷我三源对冲；总预算 9s。只有四源都干净返回“没有这首歌”才写 24h 负缓存；音乐关闭立即断开在途请求并作废旧 generation。
- **导航**：helper 从 `AmapService` 日志提取 `mIsBYDMapNaving` 早信号，`NavState` 优先按 `/data/local/tmp/.nav_sig` 切换；信号失效才进入像素兜底，减少每秒截屏。移除旧 `NavReceiver` 广播路径。
- **小窗歌词**：行号与文本未变化时不再重绘静态层，减少静止帧 CPU 与整层重建。
- **歌词候选回退**：QQ/酷狗首选候选无词时，按同名且时长差 ≤1.5s 继续尝试其他候选，修复《壁上观》等因歌手别名/首选版本无词被误杀的问题。
- **公开边界保留**：ADB 手动授权仍走 `connectForAuthorization`（`abortOnUnauthorised=false`）；胎压仍由 `TyreSource` 优先迪加 `127.0.0.1:8988`、5s 无完整四轮才回退原生映射；远程调试固定关闭，无 `ACTION_RPC`；公开 helper 与公开 framework stub 保留。
- **构建/验证**：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**160 tests / 0 failures / 0 errors / 2 skipped**；APK 1,545,771 B，sha256 `a780f52120d6210cde7f2a0189000f6bf214aebfc3c1973e79ab4c6769385dcc`。
- **产物**：`apk/lingshou-1.5.4.6.apk`。
- **历史版本**：`v1.5.2`、`v1.5.3` 的 tag、Release 与 APK 保留，只增不删。

## 1.5.3（versionCode 2696，2026-11-23）


- 空调手势由双指改为三指：三指左右滑=风量，上下滑=温度；1/2/4 指不参与，2 指恢复透传。
- `finger-touch` 只在三指时截断物理触摸并发质心采样；`AcFeature` 只在 `C,3` 亮 HUD，`GestureEngine` 只接受 3 指。
- 主界面与仪表卡片跟随车机系统 `uiMode` 切换深/浅色；空调 HUD 固定原厂深灰。
- `AcFeature` 增加对象锁 + helper `flock` 单实例锁，避免 adblib 重试拉起两个 `ls-finger-touch`。
- 构建验证：JDK 17，`assembleRelease` **BUILD SUCCESSFUL**；手势单测 17/17 通过；`versionCode=2696`、`versionName=1.5.3`。
- 产物：`apk/lingshou-1.5.3.apk`，**1,532,943 B**，sha256 `ff50272f29613564373e08be77a6bb464ca0b7e398feb8c20f4a36052356ba85`。

## 1.5.2（versionCode 2695，2026-11-23）

- “全关即停”修复：卡片总开关新增跨进程 flag，私有 `ls_card_any.flag` + 公共 `/sdcard/clusterlab_card_any.txt`。
- `ClusterCardKeeper.heal()` 全关时直接跳过；shell 看门狗 `ensure_card()` 在 `flag=0` 时不再拉空 `CardActivity`。
- 卡片总开关与 5 个元素开关改为 `commit()` 同步落盘；切完全关后进程被杀，重启不会回滚成开。
- 重开任一卡片功能写 flag=1，`:guard` 与看门狗恢复卡片；全关保持无 CardActivity 窗口。
- 远程调试仍固定关闭，无 `ACTION_RPC`。
- 构建验证：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**159 tests / 0 failures / 0 errors / 2 skipped**（新增 `CardFlagDiskTest` 5 项）。
- 产物：`apk/lingshou-1.5.2.apk`，**1,532,963 B**，sha256 `3420d2facf48ae6f5b209e39840486df811f233f888a959e5f246c7c4eb2fa70`。

## 1.5.1.3（versionCode 2694，2026-11-23）

- 四轮胎压改为**迪加优先、单一数据源**：迪加本地 HTTP `127.0.0.1:8988/api/getVal` 读取左前/右前/左后/右后四个轮位。
- 启动后给迪加 5 秒完整四轮返回窗口；四轮全部有效即锁定，此后原生 FID/instrument 映射不再写四轮，迪加临时失败保留最后一次迪加值，不回退映射。
- 5 秒内没有完整四轮才允许原生映射表写入；迪加稍后返回完整四轮会立即切回并锁定。半套数据不锁定，避免另外三轮长期空着。
- 四条并发、单条连接/读取 800ms、2 秒节拍；兼容 kPa 与 bar（<10 视为 bar，×100）。
- 冷启动不再从旧快照恢复胎压，避免开机先亮旧映射错值；油耗/电耗快照恢复不变。
- 构建验证：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；**154 tests / 0 failures / 0 errors / 2 skipped**（新增 `TyreSourceTest` 2 项）。
- 产物：`apk/lingshou-1.5.1.3.apk`，**1,532,823 B**，sha256 `8c9fdac171a7bbb9bd379fb1c2bbcff30a43d8b4e7619685a359ffae4ce88f67`。

## 许可变更（2026-11-22）

- 公开仓改为 **PolyForm Noncommercial License 1.0.0**：允许个人学习、研究、测试等非商业用途；禁止销售、付费分发、商业产品/服务集成。
- 商业使用需另行取得作者书面授权；完整条款见 `LICENSE`。
- 本次许可变更不改变当时 1.5.1.2 APK 内容，该包 SHA-256 仍为 `74bdc7bc47e639c66b71a00e2148a2c50e739b2e277a14d50edec98623be5765`。

## 1.5.1.2（versionCode 2693，2026-11-22）

- 新增公开版胎压降级：其他 BYD 车型原生 instrument 四轮 ID 不匹配、读数缺失或出现 4094 等异常值时，从迪加本地 HTTP `127.0.0.1:8988/api/getVal` 补缺失轮位。
- 原生四轮都有效时不请求迪加；迪加只补缺，不覆盖正常原生值，避免影响本车/正常车型。
- 4 轮并发请求，成功 5 秒刷新、失败 15 秒退避；迪加未装、8988 未启动、超时、参数名不匹配或返回失败时静默保持原生值，不阻断启动和刷新。
- 构建验证：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- 产物：`apk/lingshou-1.5.1.2.apk`，**1,532,827 B**，sha256 `74bdc7bc47e639c66b71a00e2148a2c50e739b2e277a14d50edec98623be5765`。

## 1.5.1.1（versionCode 2692，2026-11-22）

- 修复新车机 ADB 授权不弹窗：手动「一键启动 / 修复」改走 adblib `abortOnUnauthorised=false`，第二次 AUTH 真正发送 `AUTH_RSAPUBLICKEY`，adbd 才会显示「允许 USB 调试吗」；此前静默路径会在发公钥前主动终止，所以车机一直不弹框。
- 授权成功后可用 uid 2000 shell；首次使用需点一次「一键启动 / 修复」并在车机屏幕点「允许」。
- 开机静默探测去掉从未写入的 `adb_trusted` 门槛：已信任 key 的机器可自动连接；未信任时只静默失败，不弹 RSA。
- `AUTO_AUTH_ENABLED` 仍为 false，避免开机/心跳重复弹窗。
- 版本 `2692` / `1.5.1.1`，独立公开 release 证书。
- 构建验证：JDK 17，`testReleaseUnitTest assembleRelease` **BUILD SUCCESSFUL**；152 个单测，0 失败 / 0 错误（2 跳过）。
- 产物：`apk/lingshou-1.5.1.1.apk`，**1,528,727 B**，sha256 `99046a10c92d7fe24682d7ee18c4b93f1ec44f07a632ab403bd787c0ea22d23b`。

## 1.5.1（versionCode 2691，2026-11-21）

- 公开正式版，包名 `com.byd.ls`，独立公开 release 证书签名。
- 仪表媒体恢复：车机休眠/重启后主动重绑通知监听，媒体修复节拍从 10 分钟缩短到 2 分钟，封面与歌词不再长时间空白。
- 歌词冷启动：繁体歌名按简体候选重试，清掉旧版误写的歌词负缓存，Apple Music 等繁体曲名也能命中。
- 双指空调 HUD：固定原厂深灰配色，不再跟随系统日/夜模式变色。
- 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限。
- 移除 `com.byd.ls.action.RPC` 导出广播及其 `RemoteRpc` 本地调用入口；公开包远程调试固定关闭。
- 复核公开源码无 relay 地址、设备 ID、bootstrap/operator/ngrok 凭据、签名私钥或口令。
- 清理旧 tag、旧 Release 和旧 APK，只保留当前版本。

## 1.5-public（versionCode 2690，2026-11-21）

- 首次公开版：包名 `com.byd.ls`，独立 release 证书签名；恢复保活权限，移除 `ACTION_RPC`，远程调试固定关闭。
- 清理旧历史、旧 tag、旧 Release、旧 APK 与内部文档。
