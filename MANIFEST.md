# 发布清单

| 项 | 值 |
|---|---|
| 分发类型 | 公开版 |
| 版本 | 1.5.3 |
| versionCode | 2696 |
| 包名 | `com.byd.ls` |
| APK | `apk/lingshou-1.5.3.apk` |
| APK 大小 | 1,532,943 B |
| APK SHA256 | `ff50272f29613564373e08be77a6bb464ca0b7e398feb8c20f4a36052356ba85` |
| 签名 | 独立 release 证书，证书 SHA-256 `3920e540903d9be162d43c534c5cb8d0fc7c611c07d5036fd6e0176661780843` |
| 远程调试 | 固定关闭；无 relay/bootstrap/operator；远程 Agent 不可外部拉起 |
| 权限 | 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；无 `ACTION_RPC` |
| 胎压 | 迪加 `127.0.0.1:8988` 四轮完整返回优先并锁定；5s 无完整返回才走原生映射；锁定后不回退映射 |
| 卡片 | 全关时跨进程 flag=0，`:guard` 与 shell 看门狗都跳过，不拉空 `CardActivity`；重开任一卡片恢复 |
| 手势 | 三指左右滑=风量，上下滑=温度；1/2/4 指不参与，2 指透传 |
| 深浅 | 主界面与仪表卡片跟随车机系统 `uiMode`；空调 HUD 固定原厂深灰 |
| 源码 | `source/`，公开版源码快照 |
| 旧版本 | tag、Release、旧 APK 已删除，仅保留当前版本 |
| 私钥 | 已排除，不在仓库内 |
| 许可 | PolyForm Noncommercial License 1.0.0（仅限非商业使用；商业使用需另行授权） |

公开版固定包名 `com.byd.ls`，使用独立 release 签名。首次安装直接安装 APK；已装 1.5.2 可直接升级到 1.5.3。
