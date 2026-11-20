package com.byd.ls.service

import android.content.Context
import com.byd.ls.cluster.data.Card
import com.byd.ls.remote.RemoteAudit
import com.byd.ls.shell.ShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 60 秒健康采样环。
 *
 * 为什么需要它：现场「手势掉线、卡片还在、状态页还显示正常」这类问题，靠猜永远修不对。
 * 这里每分钟把**能从系统侧验证的事实**写成一行 CSV（不写结论、只写证据），
 * 掉线前后一对，就能定性是哪一环断的：
 *
 *   时间 / 无障碍binder / dumpsysBound段 / 我们的窗口数 / GGS实例 / 通道可用 / 卡片窗口 / 最近动作
 *
 * 全部用「读一次 dumpsys + 进程内字段」，不跑 logcat（logcat 走 adblib 会把通道占死，
 * 1.2.30 已经踩过一次）。
 */
object HealthMonitor {

    private const val FILE = "health.csv"
    private const val MAX_LINES = 3000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** 最近一条采样，界面/远程直接读，不再另外发命令 */
    @Volatile var last: String = "尚未采样"
        private set

    /** 连续"悬空"的采样次数：满 3 次（约 3 分钟）才走 force-stop 档 */
    private var hangingStreak = 0
    private var restoredOnce = false

    fun file(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE)

    fun start(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            while (true) {
                runCatching { sample(app) }
                delay(60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun sample(ctx: Context): String {
        val privileged = ShellManager.isPrivileged()
        var a11yBound = false
        var hanging = false
        var ourWindows = -1
        if (privileged) {
            // dumpsys 是**会退出**的短命令，用它取系统事实是安全的；logcat 才是坑
            val (b, h) = com.byd.ls.service.A11yHealer.queryBoundState(ctx)
            a11yBound = b
            hanging = h
            val w = kotlinx.coroutines.withTimeoutOrNull(9_000L) {
                ShellManager.exec(ctx, "dumpsys window windows", 8_000L)
            }
            if (w != null) ourWindows = w.stdout.lineSequence().count { it.contains("Window #") && it.contains("com.byd.ls") }
        }
        val ggs = runCatching { GlobalGestureService.statusText() }.getOrDefault("读取失败")
        val cardAlive = runCatching { Card.alive() }.getOrDefault(false)
        val beat = runCatching { Card.beatAge() }.getOrDefault(-1L)

        val row = buildString {
            append(fmt.format(Date())).append(',')
            append("a11yBinder=").append(A11yHealer.isBound()).append(',')
            append("dumpsysBound=").append(a11yBound).append(',')
            append("hanging=").append(hanging).append(',')
            append("ourWindows=").append(ourWindows).append(',')
            append("ggs=").append(if (ggs.startsWith("运行中")) "running" else "stopped").append(',')
            append("channel=").append(if (privileged) "ok" else "down").append(',')
            append("card=").append(if (cardAlive) "alive" else "dead").append("(beat=").append(beat).append("ms),")
            append("lastInput=").append(runCatching { GlobalGestureService.lastActionInfo() }.getOrDefault("-")).append(',')
            append("lastCmd=").append(runCatching { GlobalGestureService.lastCommandText() }.getOrDefault("-").take(40))
        }
        last = row

        // 启动首轮：如果上次重绑被打断留下备份，先把自己补回名单
        if (!restoredOnce) {
            restoredOnce = true
            runCatching { A11yHealer.restoreIfNeeded(ctx) }
        }

        // 看门狗存活自检：uid 2000 脚本是进程被杀后唯一的外部救援，
        // 真车实测它会在某个时刻静默消失（日志停在 01:12，ps 里也没有），
        // 之后一旦休眠唤醒把 App 清掉就再也回不来。这里每轮兜一次，5 分钟节流。
        if (privileged) {
            val stamp = java.io.File(ctx.filesDir, "watchdog_check.ts")
            val due = !stamp.exists() || System.currentTimeMillis() - stamp.lastModified() > 5 * 60_000L
            if (due) {
                runCatching { stamp.writeText(System.currentTimeMillis().toString()) }
                val alive = runCatching { com.byd.ls.shell.WatchdogInstaller.isRunning(ctx) }.getOrDefault(true)
                if (!alive) {
                    val r = runCatching { com.byd.ls.shell.WatchdogInstaller.ensure(ctx, "health", force = true) }
                        .getOrDefault("异常")
                    runCatching { file(ctx).appendText(fmt.format(Date()) + ",SELFHEAL watchdog " + r + "\n") }
                    RemoteAudit.log(ctx, "selfheal watchdog: " + r)
                }
            }
        }

        // 悬空计数：连续 3 次「名单有、Bound 段没有」→ 执行 force-stop + RESTART
        // （2026-11-06 真车验证：这是唯一能救回悬空的档）
        // 判据必须是**进程内真实连接**，不是 dumpsys 的字面：
        // 真车会出现"系统 Bound 段里有我们、但我们没连上"（半绑），只看 dumpsys 会永远不触发自愈。
        // 进程刚起的 60 秒不判半绑：AMS 还在 bind 过程中，此时强启只会自己打断自己
        val sinceBoot = android.os.SystemClock.elapsedRealtime() - com.byd.ls.LsApp.PROCESS_START_AT
        val a11yReallyConnected = A11yHealer.isBound() || sinceBoot < 60_000L
        if (!a11yReallyConnected) hangingStreak++ else hangingStreak = 0
        if (hangingStreak >= 5) {
            hangingStreak = 0
            val r = runCatching { A11yHealer.forceRestartIfAllowed(ctx) }.getOrDefault("异常")
            runCatching { file(ctx).appendText(fmt.format(Date()) + ",SELFHEAL force-restart " + r + "\n") }
        }
        // 第一层自愈：服务还在、但我们的窗口一条都没了 → 悬浮条被系统摘掉，直接重建
        if (privileged && ourWindows == 0 && ggs.startsWith("运行中")) {
            val r = runCatching { GlobalGestureService.rebuildOverlay() }.getOrDefault("异常")
            kotlinx.coroutines.delay(1_200L)
            val after = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(9_000L) {
                    ShellManager.exec(ctx, "dumpsys window windows", 8_000L)
                }?.stdout?.lineSequence()?.count { it.contains("Window #") && it.contains("com.byd.ls") } ?: -1
            }.getOrDefault(-1)
            runCatching { file(ctx).appendText(fmt.format(Date()) + ",SELFHEAL overlay " + r + " windows=" + after + "\n") }
            RemoteAudit.log(ctx, "selfheal overlay: " + r + " -> windows=" + after)
        }
        runCatching {
            val f = file(ctx)
            f.appendText(row + "\n")
            if (f.length() > 512 * 1024) {
                val keep = f.readLines().takeLast(MAX_LINES)
                f.writeText(keep.joinToString("\n") + "\n")
            }
        }
        return row
    }

    fun tail(ctx: Context, lines: Int): String = runCatching {
        val f = file(ctx)
        if (!f.exists()) return "（还没有 health.csv：采样还没跑满 1 分钟）"
        f.readLines().takeLast(lines.coerceIn(5, 500)).joinToString("\n")
    }.getOrElse { "读取失败：${it.message}" }

    /** 状态页用：一句话三真值（不用等 shell，读缓存） */
    fun summary(): String {
        val row = last
        if (row == "尚未采样") return "未采样"
        val w = Regex("ourWindows=(-?\\d+)").find(row)?.groupValues?.get(1) ?: "?"
        val b = Regex("dumpsysBound=(true|false)").find(row)?.groupValues?.get(1) ?: "?"
        val ch = Regex("channel=(ok|down)").find(row)?.groupValues?.get(1) ?: "?"
        val g = if (row.contains("ggs=running")) "服务在" else "服务不在"
        return "窗口=$w · 绑定=$b · 通道=$ch · $g"
    }
}
