# 源码快照说明

本目录是 **灵手 1.5.1.1 公开版（versionCode 2692 / versionName 1.5.1.1）** 的源码快照，
包名 `com.byd.ls`，与自用版同包名，但使用独立 release 签名，不能共存。

## 与发布包的关系

- 根目录 `apk/lingshou-1.5.1.1.apk` 由本源码构建；SHA256 见 `apk/SHA256SUMS.txt`。
- 源码使用独立公开 release 证书签名；证书与私钥不在仓库内。
- 公开版不能覆盖自用版；安装前必须卸载旧的自用 `com.byd.ls`，应用数据会丢失。已装公开版 1.5.1 可直接升级。

## 公开清理

已移除或替换：

- 内部交接文档、设备 ID、固定 relay 域名、bootstrap/operator/ngrok 凭据；
- `tools/remote` 远程运维工具及其数据；
- `keystore/`、签名私钥、签名口令；
- `app/build/`、`.gradle/`、`local.properties` 等构建/本地文件。

保留：

- 可构建的 Gradle 工程、Java/Kotlin 源码、构建脚本；
- `app/src/main/assets/ls-helper.apk`、`finger-touch`、`ls-watchdog.sh` 等运行时资源；
- 公开版 `RemoteConfig` 占位符（`REPLACE-ME`），公开构建恒返回关闭。

## 构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export LS_PUBLIC_STORE_FILE=/path/to/lingshou-public-release.jks
export LS_PUBLIC_STORE_PASSWORD=...
export LS_PUBLIC_KEY_ALIAS=lingshou-public
export LS_PUBLIC_KEY_PASSWORD=...
./tools/build.sh clean testReleaseUnitTest assembleRelease
```

需自备独立 release keystore；不要复用自用包的 debug 证书。
