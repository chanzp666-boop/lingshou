package com.byd.ls.shell

import android.content.Context
import com.byd.ls.config.GesturePrefs
import com.byd.ls.receiver.HeartbeatReceiver
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.BootJobService
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.util.LsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一键启动（对标 GKD 小火箭页的“启动”）。
 *
 * 目标：一次点击完成「拿到 shell 特权 → 给自己授予 development 权限 → 写无障碍 →
 * 起常驻看门狗 → 拉起前台服务 → 落持久化标记」，之后不再要求 ADB 公钥。
 *
 * 与 GKD 的差异：GKD 起的是 app_process 常驻 Binder 服务；灵手复用已有的
 * `/data/local/tmp/ls-watchdog.sh` shell 看门狗（UID 2000），实现同样“App 被杀也能拉起”
 * 的效果，代码量和故障面都小得多。
 */
object PrivilegeBootstrap {

    private const val TAG = "OneTap"

    data class Result(val ok: Boolean, val channel: String, val lines: List<String>)

    enum class Stage { LOCAL, CHANNEL, WAIT_ALLOW, GRANT, A11Y, DONE, NEED_PORT }

    data class Progress(val stage: Stage, val text: String, val waiting: Boolean = false)

    suspend fun oneTap(ctx: Context): Result = oneTap(ctx) {}

    suspend fun oneTap(ctx: Context, onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val lines = mutableListOf<String>()
        fun step(name: String, ok: Boolean, detail: String) {
            lines += "${if (ok) "✓" else "✗"} $name — ${detail.ifBlank { "完成" }}"
            LsLog.i(TAG, "$name: ok=$ok $detail")
        }
        fun warn(name: String, detail: String) {
            lines += "⚠ $name — $detail"
            LsLog.i(TAG, "$name: $detail")
        }

        onProgress(Progress(Stage.LOCAL, "启动手势服务…"))
        // 1. 先落 enabled，再拉起本地服务（服务 onCreate 会读 enabled）
        GesturePrefs.enabled = true
        GesturePrefs.writeEnabledFlag(app)
        HeartbeatReceiver.schedule(app)
        BootJobService.schedule(app)
        GlobalGestureService.start(app)
        GuardService.start(app)
        val ready = awaitService(5_000L)
        step("前台手势服务", ready, if (ready) "已就绪" else "5s 内未就绪，请看状态区")

        // 2. 权限通道：adblib 已在则直接用；否则在同一个按钮里走 ADB 授权
        onProgress(Progress(Stage.CHANNEL, "连接权限通道…"))
        // 探通道必须有硬超时：同机还有别的 App（如 com.byd.clusterassist）在抢 adbd，
        // 连接可能长时间拿不到，按钮不能因此一直停在「正在执行…」。
        var ch = withTimeoutOrNull(25_000L) { ShellManager.reprobe(app) }
            ?: ShellManager.localChannelForDisplay()
        if (!ch.isPrivileged()) {
            val authorized = AdbBootstrap.ensureAuthorized(app) { event ->
                when (event) {
                    is AdbBootstrap.Event.NeedPort ->
                        onProgress(Progress(Stage.NEED_PORT, "未检测到 ADB 端口，请打开无线调试助手"))
                    is AdbBootstrap.Event.WaitAllow ->
                        onProgress(Progress(Stage.WAIT_ALLOW, "请在车机屏幕点「允许」…", waiting = true))
                    is AdbBootstrap.Event.Done ->
                        onProgress(Progress(Stage.CHANNEL,
                            if (event.authorized) "ADB 已授权" else "ADB 未授权：${event.detail}"))
                }
            }
            if (authorized) {
                // Helper 已因 SELinux 停用（见 ShellManager.ensureHelper 注释），这里不再尝试安装。
                ch = withTimeoutOrNull(25_000L) { ShellManager.reprobe(app) }
                    ?: ShellManager.localChannelForDisplay()
            }
        }
        val privileged = ch.isPrivileged()
        if (privileged) {
            step("特权通道", true, ch.displayName)
        } else {
            warn("特权通道", "无 ADB 通道：只启动手势；自动授权/自愈需手动开无障碍")
        }

        // 3. 无障碍：有 WRITE 就自动补齐；没有则只能依赖用户手动开启
        A11yHealer.resetDebounce()
        val heal = A11yHealer.healNow(app, "oneTap")
        var bound = A11yHealer.isBound()
        step("无障碍（自动补写 / 手动校验）", bound,
            if (bound) heal else "$heal · 请到 设置→无障碍 手动开启灵手")

        if (!privileged) {
            // 没有 ADB 但有 WRITE_SECURE_SETTINGS 时，也允许按钮做一次修复
            if (!bound) {
                // repairNow 的结论原来赋给 heal 后就不再有人读（编译警告 never used）：
                // 改成单独一行 step，用户在结果区能直接看到「修了什么、最后绑没绑上」。
                val repaired = A11yHealer.repairNow(app)
                bound = A11yHealer.isBound()
                step("无障碍修复后再校验", bound, repaired)
            }
            val ok = ready && bound
            if (!ok) {
                lines += "✗ 请先到 设置→无障碍 开启灵手；开启后回到首页再点一次"
            } else {
                lines += "✓ 无 ADB 模式已就绪：手势可用，自动补权限/自愈不可用"
            }
            onProgress(Progress(Stage.DONE,
                if (ok) "完成：手势模式已就绪" else "未完成：请手动开启无障碍"))
            LsLog.i(TAG, "无 ADB 一键启动 ok=$ok\n${lines.joinToString("\n")}")
            return@withContext Result(ok, ch.displayName, lines)
        }

        // 4. 有特权：授予三个 development 权限 + 悬浮窗/后台白名单
        onProgress(Progress(Stage.GRANT, "授予权限…"))
        val grant = AdbManager.grantWriteSecureSettings(app)
        step(grant.name, grant.ok, grant.detail)
        val overlay = AdbManager.grantOverlay(app)
        step(overlay.name, overlay.ok, overlay.detail)
        val bg = AdbManager.grantBackgroundWhitelist(app)
        step(bg.name, bg.ok, bg.detail)

        // 4.1 仪表模块的附加授权（存储 / 悬浮窗 / 通知监听）：已存在的跳过，不再走单独的面板
        val cluster = runCatching {
            com.byd.ls.cluster.data.YBPrivilege.applyNow(app)
        }.getOrElse { "跳过：" + it.message }
        step("仪表模块权限（存储 / 通知监听）", !cluster.contains("未就绪"), cluster)

        // 5. 权限授予后再补一次无障碍
        onProgress(Progress(Stage.A11Y, "启用无障碍…"))
        A11yHealer.resetDebounce()
        var heal2 = A11yHealer.healNow(app, "oneTap")
        var bound2 = A11yHealer.isBound()
        if (!bound2) {
            val repaired = A11yHealer.repairNow(app)
            heal2 = "$heal2 · $repaired"
            bound2 = A11yHealer.isBound()
        }
        if (bound2) {
            step("无障碍（合并写入 + 修复 + 绑定校验）", true, heal2)
        } else {
            // 有 shell 时手势仍可用，不要把整次授权判失败；但如实提示 a11y 未绑。
            warn("无障碍未绑定（已用 shell 兜底）", heal2)
        }

        // 看门狗必须在一键启动里落：它是唯一能在休眠唤醒后把 App 拉回来的那一层
        val wd = WatchdogInstaller.ensure(app, "oneTap", force = true)
        step("shell 看门狗（UID 2000）", wd.startsWith("运行中"), wd)

        GesturePrefs.grantDone = true
        GesturePrefs.writeEnabledFlag(app)
        step("持久化配置 / 心跳", true, "手势开关已开启，开机自启标记已写入")

        val ok = lines.none { it.startsWith("✗") }
        onProgress(Progress(Stage.DONE,
            if (ok) "完成：权限与手势已就绪" else "部分完成：请查看结果"))
        LsLog.i(TAG, "一键启动完成 ok=$ok\n${lines.joinToString("\n")}")
        Result(ok, ch.displayName, lines)
    }

    /** 只补权限与无障碍，不拉起服务（给“已启动、只修权限”的场景用） */
    suspend fun repair(ctx: Context): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val lines = mutableListOf<String>()
        fun step(name: String, ok: Boolean, detail: String) {
            lines += "${if (ok) "✓" else "✗"} $name — ${detail.ifBlank { "完成" }}"
        }
        val ch = ShellManager.reprobe(app)
        if (!ch.isPrivileged()) {
            step("特权通道", false, "没有可用的 ADB 通道")
            return@withContext Result(false, ch.displayName, lines)
        }
        val grant = AdbManager.grantWriteSecureSettings(app)
        step(grant.name, grant.ok, grant.detail)
        A11yHealer.resetDebounce()
        val heal = A11yHealer.healNow(app, "repair")
        step("无障碍自愈", A11yHealer.isBound(), heal)
        Result(lines.none { it.startsWith("✗") }, ch.displayName, lines)
    }

    private suspend fun awaitService(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (GlobalGestureService.isRunning()) return true
            delay(200)
        }
        return GlobalGestureService.isRunning()
    }
}
