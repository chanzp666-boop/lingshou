# 贡献与提交规范

## 编码规范

遵循 Karpathy Guidelines：先想清楚再写、最小改动、只改必须改的、每步定义可验证目标。

- 不顺手重构相邻代码；不改未坏的东西；匹配现有风格。
- 自己改动产生的孤儿 import/变量/函数要清掉。
- 每行改动必须能追溯到需求或实车证据。

## 提交规范

- `feat(scope): …` / `fix(scope): …` / `chore(vX.Y.Z): 出 release（字节数 / sha256 前缀）并登记产物`
- 出正式包一律 `./tools/build.sh testReleaseUnitTest assembleRelease`，产物登记到 `apk/SHA256SUMS.txt` 与发布记录。
- 公开版固定包名 `com.byd.ls`，与自用版同包名但必须使用独立 release 签名；不得复用自用包 debug 签名。
- 公开版运行时包名引用必须保持 `com.byd.ls`；不要再引入 `.public` 或其他包名，否则 ADB 授权、无障碍和仪表卡片会失效。

## 迭代卫生

每轮迭代结束跑 `./tools/clean.sh` 清理构建中间产物；取证临时文件不得入库。

## 红线

- 看门狗（`assets/ls-watchdog.sh`）与无障碍自愈（`A11yHealer` / `AccessibilityWatchdog`）结构改动必须实车回归后才算完成。
- 密钥、keystore、token、设备 ID、relay 配置不入库；交接文档只记存放位置。
- 不改未授权的系统设置；不新增外部网络依赖。
