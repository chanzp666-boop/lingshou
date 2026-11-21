# 发布清单

| 项 | 值 |
|---|---|
| 分发类型 | 公开版 |
| 版本 | 1.5.1.1 |
| versionCode | 2692 |
| 包名 | `com.byd.ls` |
| APK | `apk/lingshou-1.5.1.1.apk` |
| APK 大小 | 1,528,727 B |
| APK SHA256 | `99046a10c92d7fe24682d7ee18c4b93f1ec44f07a632ab403bd787c0ea22d23b` |
| 签名 | 独立 release 证书，证书 SHA-256 `3920e540903d9be162d43c534c5cb8d0fc7c611c07d5036fd6e0176661780843` |
| 远程调试 | 固定关闭；无 relay/bootstrap/operator；远程 Agent 不可外部拉起 |
| 权限 | 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；无 `ACTION_RPC` |
| 源码 | `source/`，公开版源码快照 |
| 旧版本 | tag、Release、旧 APK 已删除，仅保留当前版本 |
| 私钥 | 已排除，不在仓库内 |

公开版与自用版同包名 `com.byd.ls`，但使用不同签名，不能共存，也不能覆盖安装。安装公开版前必须卸载旧自用版，应用数据会丢失。已装公开版 1.5.1 可直接升级到 1.5.1.1。若装过旧的 `com.byd.ls.public` 公开版，也需一并卸载，它不会被新包覆盖。
