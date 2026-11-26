package com.byd.ls

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.byd.ls.config.GesturePrefs
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.BootJobService
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 只做三件轻量事情：初始化配置、初始化日志、创建静默通知渠道。
 * 绝不在这里启动任何 UI —— 开机自启链路（BootReceiver）会绕过 Activity 直接拉起服务。
 *
 * 1.2.22：**任何进程创建都补一次服务拉起**。
 * 现场问题是"开机后不自己恢复，必须手动打开 App 等自愈"：说明 BootReceiver 的广播
 * 在部分 ROM 上没到或者到了但当时没有权限。进程被创建这件事本身（无论是被
 * JobScheduler / 闹钟 / adb 看门狗 / 用户点击叫醒的）是一个可靠的重新对齐时机，
 * 因此在这里统一做一次幂等拉起 —— 拉起逻辑本身带 enabled 判定，不会反复弹服务。
 */
class LsApp : Application() {

    /**
     * 1.4.164：SCREEN_ON 是 protected broadcast，manifest 里的 BootReceiver 收不到；
     * 进程活着时用动态注册补上，休眠唤醒/上电亮屏能立刻走 beginQuietStart 拉卡片。
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            runCatching { com.byd.ls.receiver.BootReceiver.beginQuietStart(this@LsApp, "screen-on") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sApp = this
        installCrashHandler()
        GesturePrefs.init(this)
        LsLog.init(this)
        // 1.5.3beta2：进程启动时就把媒体监听对齐；音乐/卡片关时不要等 NLS 自己醒。
        runCatching {
            com.byd.ls.cluster.data.MediaListener.setFeatureEnabled(
                com.byd.ls.cluster.FeatureGate.card() && com.byd.ls.cluster.FeatureGate.music()
            )
        }
        if (!com.byd.ls.cluster.FeatureGate.card() || !com.byd.ls.cluster.FeatureGate.vehicle()) {
            runCatching { com.byd.ls.shell.FidDirect.stop() }
        }
        if (!com.byd.ls.cluster.FeatureGate.card() || !com.byd.ls.cluster.FeatureGate.vehicle()) {
            runCatching { com.byd.ls.shell.BydDirect.unregisterStatisticListener() }
        }
        // 1.4.138：投影补发需要 app ctx（卡片重建/导航开始两条路径都用它）
        runCatching { com.byd.ls.cluster.ClusterProjection.remember(this) }
        // 1.5.0-beta2：全屏导航下由 app 进程常驻兜底补 16（不依赖卡片 onResume，卡片未起也能保持）
        if (isMainProcess()) {
            runCatching {
                if (GesturePrefs.projectionFullOnBoot) com.byd.ls.cluster.ClusterProjection.startKeepFull(this)
            }
        }
        LsLog.enabled = GesturePrefs.logEnabled
        // 仪表模块的日志开关跟随灵手同一个开关（合并后不再有独立的 /sdcard 文件开关入口）
        com.byd.ls.cluster.cfg.ClusterDebug.setMemory(GesturePrefs.logEnabled)
        com.byd.ls.shell.AdbKeyStore.init(this)
        // 仪表模块的特权执行桥接（内部转调 ShellManager，无自己的连接）
        com.byd.ls.cluster.ClusterExec.init(this)
        // 只有主进程写跨进程开关标记；:guard 的 SharedPreferences 是独立快照，
        // 在 :guard 里写会把主进程刚写入的 true 覆盖成旧值。
        if (isMainProcess()) {
            GesturePrefs.writeEnabledFlag(this)
            // 用户勾了"重新开机后自动恢复"，进程一被创建就把服务摆正（幂等）
            if (GesturePrefs.enabled) {
                com.byd.ls.receiver.BootReceiver.beginQuietStart(this, "app-onCreate")
            }
        }
        createNotificationChannel()
        // 1.4.164：动态接收亮屏；manifest 的 SCREEN_ON 在 Android 10 不会投递到静态接收器。
        if (isMainProcess()) {
            runCatching { registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON)) }
        }
        // 无障碍名单被 ROM 回写时立刻补回（主进程）
        A11yHealer.registerObserver(this)
        // 持久化 JobScheduler：重启后即使漏收 BOOT_COMPLETED 也能被唤醒
        BootJobService.schedule(this)
        // 进程重启 30s 后若 a11y 仍脱绑，做一次定向修复（GKD fixA11yService 同类兜底）
        A11yHealer.scheduleStaleRepair(this)
        // 1.4.96: 进程一起来就自检(不依赖广播) + 重启后强制重探一次特权通道
        A11yHealer.scheduleWakeCheck(this)
        if (isMainProcess()) {
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(10_000L)
                runCatching { com.byd.ls.shell.ShellManager.reprobe(this@LsApp) }
                    .onFailure { com.byd.ls.util.LsLog.w("LsApp", "启动后通道重探失败: " + it.message) }
            }
        }
        // 预热 shell 通道探测（异步，不阻塞启动）
        ShellManager.warmup(this)
        // 远程调试：安装即用，进程一被创建就接上（未配置或已关闭时自动跳过）
        com.byd.ls.remote.RemoteAgent.start(this)
        // 60s 健康采样环：只写系统侧事实（窗口数/绑定/通道/卡片），并负责第一层自愈
        if (isMainProcess()) com.byd.ls.service.HealthMonitor.start(this)
        // 每次进程被拉起都确保 uid 2000 看门狗在跑：
        // 看门狗是"进程被杀后唯一的外部救援"，而它自己也会在 ROM 清理/换脚本时消失。
        // 所有拉起路径（开机、JobScheduler、闹钟、看门狗自己）最终都会走这里，等于给自己上保险。
        if (isMainProcess()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { com.byd.ls.shell.WatchdogInstaller.ensure(this@LsApp, "app-init") }
                // 任何拉起路径（开机/看门狗/JobScheduler/闹钟/用户点开）最终都到这里：
                // 5s 后补一次无障碍自愈与卡片巡检，避免"进程起来了但功能没起来"。
                kotlinx.coroutines.delay(5_000L)
                runCatching { com.byd.ls.service.A11yHealer.healAsync(this@LsApp, "app-init", delayMs = 0) }
                runCatching { com.byd.ls.service.ClusterCardKeeper.heal(this@LsApp, "app-init") }
            }
        }
        // 开机兜底：进程被创建后再补一次卡片巡检（BootReceiver 那次可能撞在 adblib 还没握手）
        if (isMainProcess()) com.byd.ls.service.ClusterCardKeeper.heal(this, "app-init")

        LsLog.i(TAG, "LsApp onCreate, pid=${android.os.Process.myPid()} process=${currentProcessName()}")
    }

    /**
     * 全局未捕获异常落盘：车机现场没有 logcat 时，堆栈会留在 filesDir/ls-crash.log，
     * 便于用 `adb shell run-as com.byd.ls cat files/ls-crash.log` 取回定位。
     * 落盘后继续交给系统默认处理器，崩溃行为与原来一致。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = java.io.File(filesDir, "ls-crash.log")
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                f.appendText(
                    "\n===== $stamp pid=${android.os.Process.myPid()} thread=${thread.name} =====\n$sw\n"
                )
                LsLog.crash(TAG, "未捕获异常: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_MIN：无声、无振动、不弹横幅，车机下拉栏可见但基本无感。
        // Android 10 起前台服务必须挂通知，无法完全隐藏。
        val channel = NotificationChannel(
            getString(R.string.notif_channel_id),
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(channel)
    }

    private fun isMainProcess(): Boolean = try {
        android.app.Application.getProcessName() == packageName
    } catch (_: Throwable) { true }

    private fun currentProcessName(): String =
        try {
            val pid = android.os.Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "?"
        } catch (t: Throwable) { "?" }

    companion object {
        private const val TAG = "LsApp"

        @Volatile
        private var sApp: LsApp? = null

        /** 进程级 Application 引用；未初始化（如 :guard 纯子进程）时返回 null。 */
        @JvmStatic
        fun appContext(): android.content.Context? = sApp

        /**
         * 本进程的创建时刻（elapsedRealtime）。
         *
         * 上电/开机后的头几分钟无障碍几乎必然处于未绑定，此时做任何会中断自身的恢复
         * 动作都等于自杀：进程刚被广播拉起来又立刻躺下，表现就是开机后什么都没起来。
         */
        @JvmField
        var PROCESS_START_AT = android.os.SystemClock.elapsedRealtime()
    }
}
