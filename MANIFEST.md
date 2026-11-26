# 发布清单

| 项 | 值 |
|---|---|
| 分发类型 | 公开版 |
| 版本 | 1.5.4.6 |
| versionCode | 2697 |
| 包名 | `com.byd.ls` |
| APK | `apk/lingshou-1.5.4.6.apk` |
| APK 大小 | 1,545,771 B |
| APK SHA256 | `a780f52120d6210cde7f2a0189000f6bf214aebfc3c1973e79ab4c6769385dcc` |
| 签名 | 独立 release 证书，证书 SHA-256 `3920e540903d9be162d43c534c5cb8d0fc7c611c07d5036fd6e0176661780843` |
| 远程调试 | 固定关闭；无 relay/bootstrap/operator；远程 Agent 不可外部拉起 |
| 权限 | 保留 `WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；无 `ACTION_RPC` |
| ADB 授权 | 公开版保留手动授权路径：用户点“一键启动/修复”走 `connectForAuthorization`（`abortOnUnauthorised=false`）；自动授权关闭 |
| 胎压 | 迪加 `127.0.0.1:8988` 四轮完整返回优先并锁定；5s 无完整返回才走原生映射；锁定后不回退映射 |
| 卡片 | 全关时跨进程 flag=0，`:guard` 与 shell 看门狗都跳过，不拉空 `CardActivity`；重开任一卡片恢复 |
| 手势 | 三指左右滑=风量，上下滑=温度；1/2/4 指不参与，2 指透传 |
| 导航 | `AmapService` 早信号优先；信号失效才走像素兜底，减少每秒截屏 |
| 歌词 | 主源先行 1.8s、三源对冲、9s 总预算；QQ/酷狗同名同时长候选回退；音乐关闭立即取消 |
| 源码 | `source/`，公开版源码快照 |
| 旧版本 | 历史 tag、Release、旧 APK 只增不删；仓库保留 `1.5.3` 与 `1.5.4.6` |
| 私钥 | 已排除，不在仓库内 |
| 许可 | PolyForm Noncommercial License 1.0.0（仅限非商业使用；商业使用需另行授权） |

公开版固定包名 `com.byd.ls`，使用独立 release 签名。首次安装直接安装 APK；已装 1.5.2 / 1.5.3 可直接升级到 1.5.4.6。
