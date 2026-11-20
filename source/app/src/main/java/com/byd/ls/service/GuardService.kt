package com.byd.ls.service

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.byd.ls.config.GesturePrefs
import com.byd.ls.guard.DaemonGuard
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog

/**
 * 守护服务（独立进程 :guard）。
 *
 * 双进程守护的 Java 侧：
 *  - 主进程 GlobalGestureService 通过 bindService 绑住本服务；
 *  - 本服务通过 bindService + 轮询反向盯住主进程。
 *  任一方被"应用加速管理"杀掉，另一方的 onServiceDisconnected / 轮询会立刻把它拉回来。
 *
 * 配合 native 层守护（DaemonGuard）构成三重保障。三者的能力边界不同：
 *  · Java 互绑     —— 防单进程被杀，反应最快
 *  · native daemon —— 防两个 Java 进程同时被杀（它不在 ART 里，不受 GC/ANR 影响）
 *  · AlarmManager  —— 防整个应用被按 UID 整组清理（此时 native daemon 也会死）
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var mainConn: MainConnection? = null
    @Volatile private var polling = false

    /** 每 60s 点检一次：无障碍名单被 ROM 回写/服务脱绑时自动补回，顺手看仪表卡片还在不在 */
    private val healTask = object : Runnable {
        override fun run() {
            runCatching { A11yHealer.healAsync(applicationContext, "guard-60s") }
            handler.postDelayed(this, HEAL_INTERVAL_MS)
        }
    }

    /**
     * 1.4.142：卡片单独提速巡检（20s -> 6s）。
     *
     * 原来卡片跟着 [healTask] 每 20 秒才看一次，加上心跳 25 秒过期、两次复核各 5.5 秒，
     * 仪表从「其他信息」切回全屏导航后卡片最坏要等 50 秒才回来 —— 现场就是「卡片不启动」。
     * 拆出来后判活走零成本的 [Card.count]（心跳文件），只有判 0 才走 dumpsys/shell。
     * [A11yHealer] 那 20 秒周期不动：它带一堆解析，提速只会白烧 CPU。
     */
    private val cardTask = object : Runnable {
        override fun run() {
            runCatching { ClusterCardKeeper.heal(applicationContext, "guard-6s") }
            handler.postDelayed(this, CARD_INTERVAL_MS)
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            try {
                if (GesturePrefs.enabledFromDisk(applicationContext) && !isMainAlive()) {
                    LsLog.w(TAG, "轮询发现主进程手势服务不在，拉起")
                    GlobalGestureService.start(applicationContext)
                    DaemonGuard.start(applicationContext)
                }
            } catch (t: Throwable) {
                LsLog.w(TAG, "轮询异常: ${t.message}")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * adblib 连接兜底回收（:guard 自己那一份）。
     *
     * 主进程有 AccessibilityWatchdog 每 60s 收一次，但 :guard 不跑那个循环，
     * 车机实测过它会在空闲时一直挂着 ESTABLISHED 不放，占 adbd 的待授权槽位。
     */
    private val reapTask = object : Runnable {
        override fun run() {
            if (!polling) return
            runCatching { ShellManager.adbLibChannel().reapIfIdle() }
                .onFailure { LsLog.w(TAG, "adb 空闲回收异常: " + it.message) }
            handler.postDelayed(this, REAP_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GesturePrefs.init(application)
        LsLog.init(application)
        LsLog.i(TAG, "GuardService onCreate (:guard, pid=${android.os.Process.myPid()})")
        // :guard 不监听 settings：双进程 observer 会互相打醒；名单与绑定判断统一交给主进程
        // 用户关闭手势时不要通过 BIND_AUTO_CREATE 把 FGS 拉起来
        if (GesturePrefs.enabledFromDisk(applicationContext)) bindMain()
        polling = true
        handler.postDelayed(pollTask, POLL_INTERVAL_MS)
        handler.postDelayed(healTask, HEAL_INTERVAL_MS)
        handler.postDelayed(cardTask, CARD_INTERVAL_MS)
        handler.postDelayed(reapTask, REAP_INTERVAL_MS)
        // 不在这里启动 native 守护：主进程与 :guard 各起一次会造成锁竞争/pid 文件互踩。
        // native 守护统一由主进程（GlobalGestureService / BootReceiver / 心跳）启动。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户重新打开开关后，GuardService 可能已在运行但之前没绑定主服务
        if (GesturePrefs.enabledFromDisk(applicationContext) && mainConn == null) bindMain()
        return START_STICKY
    }

    override fun onDestroy() {
        LsLog.i(TAG, "GuardService onDestroy")
        polling = false
        handler.removeCallbacks(reapTask)
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(healTask)
        handler.removeCallbacks(cardTask)
        mainConn?.let { runCatching { unbindService(it) } }
        mainConn = null
        mainBinder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = GuardBinder()

    /** 仅作绑定占位，主进程用它确认 :guard 还活着 */
    class GuardBinder : android.os.Binder() {
    }

    /**
     * 用 ActivityManager.getRunningServices 判断主服务是否存活。
     *
     * API 26+ 该方法只返回调用方自己的服务，正好满足需求，且不需要任何额外权限。
     * 注意它是"最近运行过"的语义，所以再叠一层 bindService：
     * BIND_AUTO_CREATE 本身就会在主服务不存在时把它创建起来。
     */
    private fun isMainAlive(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val list = am.getRunningServices(20) ?: return false
            list.any { it.service?.className == GlobalGestureService::class.java.name }
        } catch (t: Throwable) {
            LsLog.w(TAG, "getRunningServices 失败: ${t.message}")
            false
        }
    }

    private fun bindMain() {
        mainConn = MainConnection().also { c ->
            runCatching {
                bindService(Intent(this, GlobalGestureService::class.java), c, Context.BIND_AUTO_CREATE)
            }.onFailure { LsLog.w(TAG, "绑定主服务失败: ${it.message}") }
        }
    }

    private inner class MainConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mainBinder = service as? GlobalGestureService.LocalBinder
            LsLog.i(TAG, "已绑定主进程手势服务，双进程守护生效")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mainBinder = null
            LsLog.w(TAG, "主进程手势服务断开（可能被杀）")
            mainConn?.let { runCatching { unbindService(it) } }
            mainConn = null
            if (GesturePrefs.enabledFromDisk(applicationContext)) {
                runCatching { GlobalGestureService.start(applicationContext) }
                handler.postDelayed({ bindMain() }, 500)
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName?) { /* 忽略 */ }
    }

    companion object {
        private const val TAG = "GuardSvc"
        private const val POLL_INTERVAL_MS = 5_000L
        /** adblib 空闲连接回收周期（略大于 AdbKeepAlive.IDLE_MS，避免刚用完就断） */
        private const val REAP_INTERVAL_MS = 30_000L
        private const val HEAL_INTERVAL_MS = 20_000L
        /** 卡片巡检周期（1.4.142）。判活不碰 adb，所以可以比 20s 密得多 */
        private const val CARD_INTERVAL_MS = 3_000L

        @Volatile var running: Boolean = false
            private set

        @Volatile private var mainBinder: GlobalGestureService.LocalBinder? = null

        /** 主进程无障碍 binder 的真实状态；null 表示主服务未绑定/调用失败 */
        fun mainA11yConnected(): Boolean? = mainBinder?.let {
            runCatching { it.isA11yConnected() }.getOrNull()
        }

        fun start(ctx: Context) {
            running = true
            // GuardService 不是前台服务，不能退回 startForegroundService：
            // 系统会要求 5s 内 startForeground()，否则直接 RemoteServiceException。
            // startService 失败时由主进程 bindService(BIND_AUTO_CREATE) 兜底创建。
            runCatching { ctx.startService(Intent(ctx, GuardService::class.java)) }
                .onFailure { LsLog.w(TAG, "startService 失败: ${it.message}（由主进程 bind 兜底）") }
        }

        fun stop(ctx: Context) {
            running = false
            runCatching { ctx.stopService(Intent(ctx, GuardService::class.java)) }
        }
    }
}
