# 发布清单

| 项 | 值 |
|---|---|
| 分发类型 | 公开版 |
| 版本 | 1.5.1.2 |
| versionCode | 2693 |
| 包名 | `com.byd.ls` |
| APK | `apk/lingshou-1.5.1.2.apk` |
| APK 大小 | 1,532,827 B |
| APK SHA256 | `74bdc7bc47e639c66b71a00e2148a2c50e739b2e277a14d50edec98623be5765` |
| 签名 | 独立 release 证书，证书 SHA-256 `3920e540903d9be162d43c534c5cb8d0fc7c611c07d5036fd6e0176661780843` |
| 远程调试 | 固定关闭；无 relay/bootstrap/operator；远程 Agent 不可外部拉起 |
| 权限 | 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；无 `ACTION_RPC` |
| 胎压降级 | 原生四轮缺失/异常时读迪加本地 HTTP `127.0.0.1:8988`；原生全有效时不请求，迪加不可用静默回原生 |
| 源码 | `source/`，公开版源码快照 |
| 旧版本 | tag、Release、旧 APK 已删除，仅保留当前版本 |
| 私钥 | 已排除，不在仓库内 |
| 许可 | PolyForm Noncommercial License 1.0.0（仅限非商业使用；商业使用需另行授权） |

公开版与自用版同包名 `com.byd.ls`，但使用不同签名，不能共存，也不能覆盖安装。安装公开版前必须卸载旧自用版，应用数据会丢失。已装公开版 1.5.1 / 1.5.1.1 可直接覆盖升级到 1.5.1.2。若装过旧的 `com.byd.ls.public` 公开版，也需一并卸载，它不会被新包覆盖。
