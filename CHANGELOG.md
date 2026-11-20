# 变更记录

## 1.5.1（versionCode 2691，2026-11-21）

- 公开正式版，包名 `com.byd.ls`，独立公开 release 证书签名；与自用版不能共存，也不能覆盖安装。
- versionCode 2691 / versionName 1.5.1；安装前必须卸载旧的 `com.byd.ls`，应用数据会丢失。
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

## 1.5（自用版，保留在内部工程）

- 自用包使用 debug 签名和私有远程配置，不在此公开仓库分发。
