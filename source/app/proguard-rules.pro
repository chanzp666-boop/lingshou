# 保留 JNI 入口
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.byd.ls.guard.DaemonGuard { *; }

# 保留四大组件（Manifest 已声明，混淆会导致 BOOT_COMPLETED 失效）
-keep class com.byd.ls.receiver.** { *; }
-keep class com.byd.ls.service.** { *; }

# 内置 ADB 密钥与握手通道：内置私钥常量与签名逻辑不得被混淆/裁剪
-keep class com.byd.ls.shell.AdbKeyStore { *; }

# 1.2.22：Shizuku 通道与 ShizukuCompat 反射层、自研裸协议通道都已整体删除，
# 对应的 keep / dontwarn 规则一并移除；剩余唯一特权通道是 adblib。

# adblib：AdbConnection 用线程 + 反射内部字段，且 LsAdbCrypto 继承 AdbCrypto
# （父类名被改会让 signAdbTokenPayload 的虚派发目标变掉）。
-keep class com.tananaev.adblib.** { *; }
-dontwarn com.tananaev.adblib.**

# viewBinding
-keep class com.byd.ls.databinding.** { *; }
