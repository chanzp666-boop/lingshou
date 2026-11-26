package com.byd.ls.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.byd.ls.cluster.ClusterExec
import com.byd.ls.cluster.FeatureGate
import com.byd.ls.cluster.data.Card
import com.byd.ls.cluster.data.MediaListener
import com.byd.ls.config.GesturePrefs
import com.byd.ls.util.LsLog

/**
 * 仪表卡片保活（合并自仪表助理的 :keep 进程）。
 *
 * 仪表助理原来单起一个前台服务进程专门盯卡片，和灵手的 [GuardService] 职责完全重叠：
 * 都是"发现界面/服务没了就拉回来"。合并后只保留灵手的 :guard，本对象是它每次巡检时
 * 顺手调用的一个动作，不占新进程、不常驻 adb 连接。
 *
 * 判据照搬原实现（真车验证过）：
 *  · 可见性只问窗口管理器（dumpsys window windows 里 isVisible=true），
 *    因为 ActivityRecord 在、窗口是 GONE 时心跳仍然新鲜，会误判"活着"再也不拉；
 *  · 连续 [MISS_LIMIT] 次看不见才动手，避免和开机自启抢跑；
 *  · 熄屏时不动手，省电也避免点火前的无谓拉起；
 *  · 通知监听掉线只按 [MEDIA_FIX_GAP_MS] 节流重绑，避免反复 allow_listener 让媒体短暂断流。
 */
object ClusterCardKeeper {

    private const val TAG = "CardKeeper"

    /** 两次重拉之间的最小间隔（跨进程文件时间戳判定） */
    private const val RESTART_GAP_MS = 15_000L
    private const val RESTART_STAMP = "/sdcard/clusterlab_card_restart.txt"

    /**
     * 拉卡片前后给 NavProbe 的让路窗口。
     *
     * NavProbe 每轮一次 screencap，每次一条新 adblib 连接；adbd 的连接槽位有限，
     * 实测每秒 1 次握手时关键的 am start-activity 会被回
     * "Stream open actively rejected by remote peer"，卡片就永远拉不起来。
     */
    private const val PROTECT_MS = 2_500L

    /** 通知监听重绑的最小间隔 */
    private const val MEDIA_FIX_GAP_MS = 2 * 60_000L

    /** 进程刚起来时先别抢跑（卡片窗口创建要几百毫秒） */
    private const val BORN_GUARD_MS = 3_000L

    /** 通道未就绪时的短重试：间隔 8s，一次开机周期内最多 6 次（不累积到下个周期） */
    private const val READY_RETRY_MS = 8_000L
    private const val READY_RETRY_MAX = 6

    /**
     * 确认"看不见"要连续几轮（1.4.134）。
     *
     * 旧实现一轮判 0 就直接 [Card.restartViaShell]（CLEAR_TASK 清任务重建），
     * 只要那一轮判活本身不可靠 —— 通道刚起、心跳还是上个进程的、dumpsys 被
     * adbd 拒绝 —— 正在显示的卡片就会被推倒重画。必须二次复核。
     */
    private const val MISS_LIMIT = 2

    /** 复核间隔：必须大于 [MIN_HEAL_GAP_MS]，否则排进来的 heal 会被自己的节流拦掉 */
    private const val MISS_RETRY_MS = 2_500L

    private val bornAt = SystemClock.elapsedRealtime()
    /** 前台/多路径同时触发时的最小间隔，避免重复跑 dumpsys */
    private const val MIN_HEAL_GAP_MS = 2_000L
    private var lastHealAt = 0L
    /** 已排过一次「跳出保护窗」的延迟补拉（见 heal 的 BORN_GUARD 分支） */
    private var bornDeferred = false
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var readyRetries = 0
    /** 连续判"不可见"的轮数（1.4.134 二次复核） */
    private var missCount = 0
    /** 上一次判“不可见”的时刻；不同入口（boot/app-init/guard）不能替对方凑第二轮。 */
    private var lastMissAt = 0L
    /** 1.4.167：首次不可见先普通启动，不直接用 CLEAR_TASK 清任务。 */
    private var startAttempted = false
    private var lastMediaFix = 0L

    /**
     * 1.4.165：亮屏恢复后的窗口稳定期。
     *
     * 休眠时窗口会被系统置 GONE；刚亮屏时窗口/心跳还没重新就绪，旧逻辑会在
     * 2.5s 后把正在恢复的卡片判死并 CLEAR_TASK 重建，现场就是"先出现、几秒后
     * 自己关掉再重开"。只在检测到熄屏→亮屏的边沿后生效，不拖慢首次开机。
     */
    private const val WAKE_SETTLE_MS = 8_000L
    private var lastInteractive = true
    private var wakeAt = 0L

    /** 巡检入口：同步返回，真正的 shell 动作丢到后台线程（绝不能占住 :guard 主线程） */
    fun heal(ctx: Context, reason: String) {
        if (!GesturePrefs.cardAnyFromDisk(ctx)) {
            LsLog.i(TAG, "跳过（没有任何卡片功能）: " + reason)
            return
        }
        if (!GesturePrefs.clusterEnabled) {
            LsLog.i(TAG, "跳过（卡片开关关）: " + reason)
            return
        }
        val age = SystemClock.elapsedRealtime() - bornAt
        if (age < BORN_GUARD_MS) {
            // 【1.4.8 开机提速】旧实现直接 return：而开机时 app-init / boot-fast 恰好落在
            // 这 3 秒保护窗内 → 唯一一次有效调用被静默丢弃，卡片要等下一轮（心跳 60s）。
            // 现在改为延迟到保护窗结束补一次；只补一次，避免多路径一起排风暴。
            if (!bornDeferred) {
                bornDeferred = true
                retryHandler.postDelayed(
                    { runCatching { heal(ctx, reason + "/defer") } },
                    BORN_GUARD_MS - age + 50L
                )
                LsLog.i(TAG, "保护窗内($age ms)，已排一次延迟补拉: " + reason)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastHealAt < MIN_HEAL_GAP_MS) return
        lastHealAt = now
        val app = ctx.applicationContext
        Thread({
            runCatching { healCard(app, reason) }
            runCatching { healMedia() }
        }, "card-heal").apply { isDaemon = true }.start()
    }

    private fun healCard(ctx: Context, reason: String) {
        if (!ClusterExec.ready()) {
            // 开机/唤醒瞬间 adblib 往往还在握手：催一次通道，并自己在 8s 后重试，
            // 否则这一轮机会被丢到下一轮闹钟，卡片要等 60~120s 才可能回来。
            runCatching { ClusterExec.connect(ctx) }
            if (readyRetries < READY_RETRY_MAX) {
                readyRetries++
                retryHandler.postDelayed({ heal(ctx, reason + "/r" + readyRetries) }, READY_RETRY_MS)
            }
            return
        }
        readyRetries = 0
        val now = SystemClock.elapsedRealtime()
        val interactive = screenOn(ctx)
        // 只在检测到熄屏→亮屏的边沿后开稳定期；进程首次启动时不做，避免拖慢开机。
        if (interactive && !lastInteractive) {
            wakeAt = now
            missCount = 0
            LsLog.i(TAG, "亮屏，进入 ${WAKE_SETTLE_MS}ms 窗口稳定期（" + reason + "）")
        }
        lastInteractive = interactive
        if (!interactive) {
            missCount = 0
            LsLog.i(TAG, "熄屏，跳过判活（" + reason + "）")
            return
        }
        // 1.4.134：判活是三态，不是二态。
        //   count() == null —— 判不出来（通道没起来 / 心跳文件是上个进程留下的）。
        //   旧实现用 alive() 把 null 折成 false，于是开机那几十秒必然"判死"，
        //   触发 CLEAR_TASK 重建：实车表现就是"卡片先显示出来，几秒后自己关掉再重开"
        //   （1.4.133 00:06:04 起进程 → 00:06:44 判死 → 00:06:46.7 已请求重建）。
        val c = runCatching { Card.count() }.getOrNull()
        if (c == null) {
            LsLog.i(TAG, "卡片判活不可用（" + reason + "），本轮不动手")
            return
        }
        if (c > 0) {
            missCount = 0
            lastMissAt = 0L
            startAttempted = false
            return
        }
        // 窗口/心跳还在恢复时不要判死；否则会把刚显示出来的卡片 CLEAR_TASK 重建。
        if (now - wakeAt < WAKE_SETTLE_MS) {
            missCount = 0
            LsLog.i(TAG, "亮屏稳定期内，暂不判死（" + reason + "）")
            return
        }
        // 确认不在需要连续两轮；第一轮只记日志并安排复核。
        // 其他入口（boot-fast / app-init / guard / a11y）可能在复核计时器之前再次进来，
        // 不能把这次独立调用当成“第二轮”，否则会在卡片还没 attach 时直接 CLEAR_TASK。
        if (missCount > 0 && now - lastMissAt < MISS_RETRY_MS) {
            LsLog.i(TAG, "复核未到点，忽略本次判死（" + reason + "）")
            return
        }
        lastMissAt = now
        missCount++
        if (missCount < MISS_LIMIT) {
            if (!startAttempted) {
                startAttempted = true
                val sr = runCatching { Card.start() }.getOrElse { "失败：" + it.message }
                LsLog.i(TAG, "首次不可见，先普通启动（不清理任务）：" + sr)
            }
            LsLog.i(TAG, "卡片疑似不可见（" + reason + "），第 " + missCount + " 次，稍后复核")
            retryHandler.postDelayed({ runCatching { heal(ctx, reason + "/miss" + missCount) } }, MISS_RETRY_MS)
            return
        }
        missCount = 0
        if (!screenOn(ctx)) {
            LsLog.i(TAG, "熄屏跳过（" + reason + "）")
            return
        }
        // 计数器不能放进程内：主进程（无障碍/前台）与 :guard 各有一份静态状态，
        // 双方各自累加到"第 1 次"就永远到不了阈值，卡片永远不会被重拉。
        // 改用 /sdcard 上的时间戳做跨进程节流。
        val nowWall = System.currentTimeMillis()
        if (nowWall - lastRestartStamp() < RESTART_GAP_MS) return
        markRestartStamp(nowWall)
        LsLog.i(TAG, "卡片不可见（" + reason + "），重拉")
        ClusterExec.protectFor(PROTECT_MS)
        val r = runCatching { Card.restartViaShell() }.getOrElse { "失败：" + it.message }
        LsLog.i(TAG, "卡片不在（" + reason + "），重拉：" + r)
    }

    /** 上次重拉时间（跨进程）：文件读写失败时退化为 0 = 允许立刻重拉 */
    private fun lastRestartStamp(): Long =
        runCatching { java.io.File(RESTART_STAMP).readText().trim().toLong() }.getOrDefault(0L)

    private fun markRestartStamp(t: Long) {
        runCatching { java.io.File(RESTART_STAMP).writeText(t.toString()) }
    }

    private fun healMedia() {
        if (!FeatureGate.music()) return
        // :guard 与主进程不共享静态状态，MediaListener.connected 在这里永远是 false。
        // 以主进程每 2s 刷新的心跳文件为准；旧实现只看静态变量，导致每次巡检都误判掉线。
        if (mediaHeartbeatAlive()) return
        val now = SystemClock.elapsedRealtime()
        val gap = if (ClusterExec.ready()) MEDIA_FIX_GAP_MS else 20_000L
        if (now - lastMediaFix < gap) return
        lastMediaFix = now
        // 实测 app=null 时 requestRebind 会直接返回成功但不产生绑定；
        // 因此优先用 shell 的 disallow/allow 强制系统重绑，通道没起来才退回进程内请求。
        if (ClusterExec.ready()) {
            runCatching { Card.rebindMedia() }
            LsLog.i(TAG, "通知监听未绑定，已 shell 重绑")
            return
        }
        if (runCatching { MediaListener.requestRebindNow() }.getOrDefault(false)) {
            LsLog.i(TAG, "通知监听掉线，ADB 未就绪，已请求系统重绑")
        }
    }

    /** 主进程心跳是否新鲜且 connected=1；跨进程唯一可靠的“监听真的绑上了”证据。 */
    private fun mediaHeartbeatAlive(): Boolean {
        val f = java.io.File(MediaListener.HEARTBEAT)
        if (!f.exists()) return false
        val age = System.currentTimeMillis() - f.lastModified()
        if (age < -2_000L || age > 8_000L) return false
        return runCatching {
            f.readText().lineSequence()
                .firstOrNull { it.startsWith("connected=") }
                ?.substringAfter('=')?.trim() == "1"
        }.getOrDefault(false)
    }

    private fun screenOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /** 单测用：重置节流与计数 */
    internal fun resetForTest() {
        retryHandler.removeCallbacksAndMessages(null)
        readyRetries = 0
        missCount = 0
        lastMediaFix = 0L
    }
}
