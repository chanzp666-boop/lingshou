package com.byd.ls.shell

import android.content.Context
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.A11yList
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.util.LsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一键授权与系统权限配置。
 *
 * 全部命令都经 [ShellManager] 走 shell(UID 2000) 通道执行，App 自身 UID 没有这些权限。
 * 1.2.22 起通道只有 adblib 一路（Shizuku 与自研裸协议客户端已整体删除）。
 *
 * ⚠️ 与原始需求的两处刻意差异（都是为了让它在 DiLink 4.0 上真的能用）：
 *
 * 1. 无障碍开启命令**不能**直接覆盖写入。原方案
 *      settings put secure enabled_accessibility_services com.byd.ls/.LsAccessibilityService
 *    会把车机上已启用的其它无障碍服务（例如 GKD / 迪友 / 迪加）整个顶掉，而且少了
 *    accessibility_enabled=1 在 Android 10 上经常不生效。这里改成：读原值 → 按 ':' 合并
 *    去重（只追加缺的自己）→ 写回 → 再置 enabled=1，并且带限流与冷却。
 *
 * 2. 组件名写全限定形式 com.byd.ls/com.byd.ls.service.LsAccessibilityService。
 *    缩写形式 pkg/.Class 在部分定制 ROM 的 settings 解析里会失败。
 */
object AdbManager {

    private const val TAG = "AdbManager"

    const val PKG = "com.byd.ls"

    /** 从 `cmd appops set <pkg> <OP> allow` 里取出 OP 名，用于「已生效就跳过」 */
    private val OPS_GET_PATTERN = Regex("appops set \\S+ (\\S+)")
    const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    const val DUMP = "android.permission.DUMP"

    // ---------- 报告模型 ----------
    data class Step(val name: String, val ok: Boolean, val detail: String) {
        override fun toString() = "${if (ok) "✓" else "✗"} $name${if (detail.isBlank()) "" else " — $detail"}"
    }

    data class Report(val channel: String, val privileged: Boolean, val steps: List<Step>) {
        val criticalFailed: List<Step> get() = steps.filter { !it.ok && !it.name.startsWith("[可选]") }
        val allCriticalOk: Boolean get() = criticalFailed.isEmpty()
        fun format(): String = buildString {
            append("通道：$channel\n")
            append("特权：${if (privileged) "有（可注入按键 / 改 secure settings）" else "无"}\n\n")
            steps.forEach { append(it.toString()).append('\n') }
        }
    }

    // =====================================================================
    //  开启 / 连接本地 ADB
    // =====================================================================



    /**
     * 尝试已知 ADB 端点（1.2.22 起只剩本地 127.0.0.1:5555）。
     *
     * 只做静默握手（abortOnUnauthorised=true）：key 已被信任就自动连上，未信任只会
     * 失败，不会让 adbd 弹 RSA。需要授权时由用户点「一键启动/修复」走 [AdbBootstrap]。
     * 成功后后续 [PrivilegeBootstrap.repair] 就能重新 `pm grant` / 补无障碍。
     */
    suspend fun autoConnectKnown(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (!AdbEndpoint.probe()) return@withContext false
        // 用 adblib 直接静默握手：key 已在 adb_keys 里时不会弹窗
        val ok = ShellManager.reprobe(ctx).isPrivileged()
        LsLog.i(TAG, "开机静默连接 ADB ${AdbEndpoint.DEFAULT_HOST}:${AdbEndpoint.DEFAULT_PORT} -> $ok")
        ok
    }

    // =====================================================================
    //  一键授权主流程
    // =====================================================================
    suspend fun oneClickGrant(ctx: Context): Report {
        val ch = ShellManager.reprobe(ctx)          // 强制重探，覆盖 adbd 刚重启的窗口期
        if (!ch.isPrivileged()) {
            LsLog.w(TAG, "一键授权中止：当前通道无特权（${ch.displayName}）")
            return Report(ch.displayName, false, listOf(
                Step("获取特权 shell 通道", false,
                    "127.0.0.1:5555 握手失败。请点「一键启动 / 修复」，在车机屏幕上点「允许」；" +
                        "端口未监听时先打开车机 ADB 设置里的开关。")
            ))
        }

        val steps = mutableListOf<Step>()
        steps += grantOverlay(ctx)
        steps += grantBackgroundWhitelist(ctx)
        steps += grantWriteSecureSettings(ctx)
        steps += enableAccessibility(ctx)
        steps += vendorSpecificTolerated(ctx)
        steps += verify(ctx)

        val report = Report(ch.displayName, true, steps)
        LsLog.i(TAG, "一键授权完成 allCriticalOk=${report.allCriticalOk}\n${report.format()}")
        return report
    }

    // ---------- 1. 悬浮窗（边缘手势条依赖它） ----------
    suspend fun grantOverlay(ctx: Context): Step {
        val r = ShellManager.exec(ctx, "cmd appops set $PKG SYSTEM_ALERT_WINDOW allow")
        val check = ShellManager.exec(ctx, "cmd appops get $PKG SYSTEM_ALERT_WINDOW")
        val ok = r.success && check.stdout.contains("allow", true)
        return Step("悬浮窗权限 SYSTEM_ALERT_WINDOW", ok,
            if (ok) "已允许" else "命令返回 ${r.exitCode}：${firstLine(r.stdout)} ${firstLine(check.stdout)}")
    }

    // ---------- 2. 后台运行 / 保活白名单 ----------
    suspend fun grantBackgroundWhitelist(ctx: Context): Step {
        // Android 10 AOSP 原生存在的 op；BOOT_COMPLETED 是普通广播权限（Manifest 声明即授予），
        // 不是 appop，下发 `cmd appops set ... BOOT_COMPLETED` 会报 Unknown operation string。
        val guaranteed = listOf(
            "cmd appops set $PKG RUN_IN_BACKGROUND allow",
            "cmd appops set $PKG WAKE_LOCK allow",
            // 加入 doze 白名单，熄火待机后不被冻结
            "dumpsys deviceidle whitelist +$PKG",
            // 应用待机分桶拉到 active，降低被"应用加速管理"清理的概率
            "am set-standby-bucket $PKG active",
            "am set-inactive $PKG false"
        )
        // RUN_ANY_IN_BACKGROUND 是 Android 12+ 的 op；老固件不识别就跳过，不计入失败。
        val tolerated = listOf(
            "cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow"
        )
        val failed = mutableListOf<String>()
        var skippedOps = 0
        for (c in guaranteed) {
            // 已经是 allow 就跳过：这些 op 一次授权后就永久生效，而每条命令都要 fork
            // 一个 app_process（200-400ms）。一键授权里六条全跳过能省 1.5 秒以上。
            if (isAppOpAlreadyAllowed(ctx, c)) { skippedOps++; continue }
            val r = ShellManager.exec(ctx, c, 6_000L)
            if (!r.success) failed += "$c → ${firstLine(r.stdout)}"
        }
        val toleratedRejected = mutableListOf<String>()
        for (c in tolerated) {
            val r = ShellManager.exec(ctx, c, 6_000L)
            if (!r.success) toleratedRejected += c
        }
        // 回读校验最关键的一项（RUN_IN_BACKGROUND 在 API 28+ 一定存在）
        val verify = ShellManager.exec(ctx, "cmd appops get $PKG RUN_IN_BACKGROUND")
        val reallyOk = verify.stdout.contains("allow", true)
        val detail = when {
            reallyOk && failed.isEmpty() -> buildString {
                append("appops + deviceidle + standby-bucket 全部生效")
                if (skippedOps > 0) append("（$skippedOps 项已存在，跳过）")
                if (toleratedRejected.isNotEmpty()) append("；旧固件忽略：${toleratedRejected.joinToString("; ")}")
            }
            reallyOk -> "已生效，但以下命令返回非零：${failed.joinToString("; ")}"
            else -> "校验未通过：${firstLine(verify.stdout)} / ${failed.joinToString("; ")}"
        }
        return Step("后台运行 / 保活白名单", reallyOk && failed.isEmpty(), detail)
    }

    /**
     * 这条命令对应的状态是不是已经是目标值。
     *
     * 只为「可幂等且开销大」的那几条命令服务：`cmd appops get` / `am get-standby-bucket`
     * 走一次 binder 就能问出来，比 fork 一个 app_process 去 set 便宜一个数量级。
     * 判定不了一律返回 false —— 宁可多执行一次，也不要把该生效的跳过。
     */
    private suspend fun isAppOpAlreadyAllowed(ctx: Context, cmd: String): Boolean {
        // 注意：deviceidle whitelist / am set-inactive 这类命令里没有 appops 的 op 名，
        // 所以正则只能用于 appops set，其余走下面的 contains 分支。
        val probe = when {
            cmd.contains("appops set") -> {
                val op = OPS_GET_PATTERN.find(cmd)?.groupValues?.get(1) ?: return false
                "cmd appops get $PKG $op"
            }
            cmd.contains("set-standby-bucket") -> "am get-standby-bucket $PKG"
            cmd.contains("set-inactive") -> "am get-inactive $PKG"
            // doze 白名单：已在名单里就不用再写一遍（dumpsys 的输出里按 user,<pkg>,<uid> 分行列）
            cmd.contains("deviceidle whitelist +") -> "dumpsys deviceidle whitelist"
            else -> return false
        }
        val out = ShellManager.exec(ctx, probe, 4_000L)
        if (!out.success) return false
        val body = out.stdout.trim()
        return when {
            cmd.contains("set-standby-bucket") -> body == "10"
            cmd.contains("set-inactive") -> body.contains("idle=false", ignoreCase = true)
            cmd.contains("deviceidle whitelist +") -> body.contains(PKG)
            else -> body.contains("allow", ignoreCase = true)
        }
    }

    // ---------- 3. 无障碍：read-modify-write，绝不覆盖已有服务 ----------
    /**
     * 优先用 App 自身 WRITE_SECURE_SETTINGS 直写；未授予时自动降级 shell。
     * 具体策略在 [A11yHealer]：保留他人项、定向 rebind、绑定校验、日志留痕。
     */
    suspend fun enableAccessibility(ctx: Context): Step {
        A11yHealer.resetDebounce()
        val detail = A11yHealer.healNow(ctx, "oneClickGrant")
        val enabledList = A11yHealer.readEnabled(ctx)
        val inList = A11yList.contains(enabledList)
        val bound = A11yHealer.isBound()
        val ok = inList && bound
        return Step(
            "开启无障碍服务（read-modify-write + 绑定校验）", ok,
            if (ok) "已启用且已绑定。$detail"
            else "名单含本服务=$inList，已绑定=$bound。$detail"
        )
    }

    // ---------- 3.5 两个 development 权限（只留有用的） ----------
    /**
     * 只授予两个真正用得上的权限：
     *  · WRITE_SECURE_SETTINGS — App 自己写回无障碍名单，不再依赖 ADB 在线；
     *  · DUMP — 本地 dumpsys accessibility 校验绑定，省一次 shell 往返。
     * GET_APP_OPS_STATS 已去掉：现场要求只保留必要权限，而 appops 读状态用 shell 已经够。
     */
    suspend fun grantWriteSecureSettings(ctx: Context): Step {
        val perms = listOf(WRITE_SECURE, DUMP)
        val out = StringBuilder()
        val granted = LinkedHashMap<String, Boolean>()
        var skipped = 0
        for (p in perms) {
            // 已授予就跳过：pm grant 对 development 权限幂等，但每次都要 fork 一个
            // app_process（200-400ms），一键授权里累积起来就是肉眼可见的卡顿。
            if (ctx.checkSelfPermission(p) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                granted[p] = true
                skipped++
                out.append("✓ ").append(p.substringAfterLast('.')).append("（已授予，跳过）\n")
                continue
            }
            val r = ShellManager.exec(ctx, "pm grant $PKG $p", 8_000)
            val ok = runCatching {
                ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            granted[p] = ok
            out.append(if (ok) "✓ " else "✗ ")
                .append(p.substringAfterLast('.'))
                .append(if (ok) "" else "（${firstLine(r.stdout)}）")
                .append('\n')
        }
        val writeOk = granted[WRITE_SECURE] == true
        return Step(
            "[可选]授予 WRITE_SECURE_SETTINGS / DUMP", writeOk,
            if (writeOk) {
                "已授予：无障碍自愈与绑定校验可脱离 ADB 独立完成" +
                    (if (skipped > 0) "（$skipped 项已存在，跳过）" else "") + "\n" + out.toString().trim()
            } else {
                "WRITE_SECURE_SETTINGS 未授予，自愈降级为 shell 通道，需 ADB 在线\n" + out.toString().trim()
            }
        )
    }

    /**
     * 厂商定制项。DiLink 的"应用加速管理"可能使用非 AOSP 的 appops（AUTO_START / BOOT_START 等），
     * 这些 op 在标准 AOSP 上不存在，命令会返回非零 —— 属于预期，所以标记为「[可选]」，
     * 不计入 criticalFailed，只在日志里留痕便于现场排查。
     */
    private suspend fun vendorSpecificTolerated(ctx: Context): Step {
        val ops = listOf("AUTO_START", "BOOT_START", "BACKGROUND_START_ACTIVITY", "START_FOREGROUND")
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (op in ops) {
            val r = ShellManager.exec(ctx, "cmd appops set $PKG $op allow", 5_000)
            if (r.success) accepted += op else rejected += op
        }
        LsLog.i(TAG, "厂商定制 appops 生效=$accepted 不支持=$rejected")
        return Step("[可选]DiLink 厂商定制 appops", true,
            if (accepted.isEmpty()) "本机未识别到自定义 op（正常，标准 appops 已覆盖）"
            else "已额外放开：${accepted.joinToString()}")
    }

    // ---------- 4. 回读校验 ----------
    private suspend fun verify(ctx: Context): Step {
        val a11y = ShellManager.exec(ctx, "settings get secure accessibility_enabled").trimmed
        val inList = A11yList.contains(A11yHealer.readEnabled(ctx))
        val bound = A11yHealer.isBound()
        val running = ShellManager.exec(ctx, "pidof $PKG").trimmed
        val ok = a11y == "1" && inList && bound && running.isNotBlank()
        return Step("授权结果自检", ok,
            "accessibility_enabled=$a11y，名单含本服务=$inList，已绑定=$bound，进程 pid=${running.ifBlank { "无" }}")
    }



    // =====================================================================
    //  状态查询
    // =====================================================================
    suspend fun statusSnapshot(ctx: Context): LinkedHashMap<String, String> {
        val m = ShellManager.statusLines()
        // 只有特权通道才值得跑这些 shell 命令；用本机 exec 会全部失败并刷一行"失败"
        if (!ShellManager.isPrivileged()) {
            m["说明"] = "无特权通道，以上为本地可见状态"
            return m
        }
        val bg = ShellManager.exec(ctx, "cmd appops get $PKG RUN_IN_BACKGROUND")
        m["后台运行权限"] = if (bg.stdout.contains("allow", true)) "已允许" else "未允许"
        val pid = ShellManager.exec(ctx, "pidof $PKG")
        m["灵手进程"] = pid.trimmed.ifBlank { "未运行" }
        return m
    }

    private fun firstLine(s: String): String =
        s.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: ""
}
