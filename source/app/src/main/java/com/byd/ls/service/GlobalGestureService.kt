package com.byd.ls.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.byd.ls.R
import com.byd.ls.ac.AcFeature
import com.byd.ls.config.GesturePrefs
import com.byd.ls.guard.DaemonGuard
import com.byd.ls.receiver.HeartbeatReceiver
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat

/**
 * 手势监听服务（主进程，前台服务）。
 *
 * 职责：
 *  1. 持有三条边缘悬浮窗，把触摸喂给 [GestureRecognizer]
 *  2. 判定结果交给 [KeyEventExecutor]，经 shell 通道注入系统按键
 *  3. 监听 SharedPreferences，界面改动（开关/宽度/灵敏度）实时生效，无需重启服务
 *  4. 接收 [LsAccessibilityService] 的两路信号：输入法可见性、前台应用包名
 *  5. 启动 native 守护进程，并与 :guard 进程互相绑定
 *
 * 开机自启时由 [com.byd.ls.receiver.BootReceiver] 直接拉起本服务，**不经过任何 Activity**。
 */
class GlobalGestureService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var recognizer: GestureRecognizer
    private lateinit var overlay: EdgeOverlayController
    private lateinit var executor: KeyEventExecutor

    private var guardConn: GuardConnection? = null

    // =====================================================================
    override fun onCreate() {
        super.onCreate()
        // 1.4.138：startForegroundService() 之后系统只给 5 秒进前台，超时直接判 ANR 杀整个进程
        // （实车 01:24:45：Load 10.44，主线程排队超时，MainActivity 与 CardActivity 一起被强杀）。
        // 这里是 onCreate 的第一件事；通知 channel 由 LsApp.createNotificationChannel() 预建。
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
            .onFailure { LsLog.w(TAG, "onCreate 立即 startForeground 失败: " + it.message) }
        setInstance(this)
        GesturePrefs.init(application)
        LsLog.init(application)
        LsLog.i(TAG, "GlobalGestureService onCreate，enabled=${GesturePrefs.enabled} " +
            "边宽 左=${GesturePrefs.edgeLeftDp} 右=${GesturePrefs.edgeRightDp} 底=${GesturePrefs.edgeBottomDp}dp " +
            "sens=${GesturePrefs.sensitivity}")

        val density = resources.displayMetrics.density
        recognizer = GestureRecognizer(density, onGesture = { gesture -> onGesture(gesture) }, onTapAt = { x, y -> executor.tapAt(x, y) }, onLongPressAt = { x, y -> executor.tapAt(x, y, longClick = true) })
        overlay = EdgeOverlayController(
            applicationContext, recognizer,
            A11yOverlayBridge.currentHost(applicationContext)
        )
        recognizer.onGestureGrab = { runCatching { overlay.grabRest() } }
        recognizer.onGestureRelease = { runCatching { overlay.releaseRest() } }
        executor = KeyEventExecutor(applicationContext, scope)

        // 无障碍连上/断开时切换 window type：无障碍优先，失败才回退应用悬浮窗。
        // 整个切换必须 runCatching：无障碍 bind 由系统回调，此刻 overlay 可能刚好在重建，
        // setHost 抛异常会顺着 onServiceConnected 冒到系统栈（现场 dump 里的
        // A11yOverlayBridge.onConnected -> GlobalGestureService$onCreate$4.invoke 崩溃栈）。
        A11yOverlayBridge.listener = { host ->
            scope.launch {
                runCatching {
                    overlay.setHost(host)
                    if (GesturePrefs.enabled && !overlay.attached) {
                        overlay.attach(
                            GesturePrefs.edgeLeftDp, GesturePrefs.edgeRightDp,
                            GesturePrefs.edgeBottomDp, GesturePrefs.debugEdgeVisible
                        )
                    }
                }.onFailure { LsLog.w(TAG, "切换悬浮窗宿主失败（已忽略）: ${it.message}") }
            }
        }

        GesturePrefs.registerListener(this)
        applyConfig(full = true)

        // 保活三件套：native 守护 + :guard 进程互绑 + AlarmManager 心跳
        DaemonGuard.start(applicationContext)
        bindGuard()
        HeartbeatReceiver.schedule(applicationContext)
        runCatching { BootJobService.schedule(applicationContext) }
        runCatching { com.byd.ls.receiver.BootReceiver.scheduleBootAssist(applicationContext) }

        // 无障碍名单/绑定自愈：服务起来先补一次，之后由 AccessibilityWatchdog 周期触发
        A11yHealer.healAsync(applicationContext, "GlobalGestureService.onCreate")
        AccessibilityWatchdog.start(applicationContext, scope)

        // 无障碍服务可能比本服务先起来，补一次状态同步
        LsAccessibilityService.pendingImeVisible?.let { overlay.setBottomEnabled(!it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兜底：任何路径进来都再确认一次前台状态（幂等，重复调用无害）
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
        when (intent?.action) {
            ACTION_STOP -> {
                LsLog.i(TAG, "收到停止指令：只停手势功能，自愈守护保留")
                GesturePrefs.enabled = false
                applyConfig(full = true)
                return START_STICKY
            }
            ACTION_APPLY_CONFIG -> {
                applyConfig(full = true)
            }
        }
        // 被系统杀掉后自动重建，这是保活的第一道防线
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉 App 时的兜底：请求 1 秒后的心跳，把服务重新拉起。
     * 直接在 onTaskRemoved 里 startForegroundService 在 Android 10 上会被后台启动限制拦截，
     * 走 AlarmManager 由 system_server 触发更可靠。
     */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        if (GesturePrefs.enabled) {
            LsLog.i(TAG, "onTaskRemoved：安排心跳重新拉起服务")
            HeartbeatReceiver.scheduleSoon(applicationContext, 1_000L)
            // 划掉任务后系统会把卡片窗口一起收回（同进程），这里立刻补一次；
            // 手势服务自己被 ROM 清掉时还能靠 1 秒后的心跳，卡片则只能靠这一次。
            if (GesturePrefs.clusterEnabled) {
                Thread {
                    runCatching { ClusterCardKeeper.heal(applicationContext, "task-removed") }
                }.apply { isDaemon = true; name = "ls-taskrm-card"; start() }
            }
        }
    }

    override fun onDestroy() {
        LsLog.i(TAG, "GlobalGestureService onDestroy")
        runCatching { AcFeature.stop() }
        runCatching { GesturePrefs.unregisterListener(this) }
        A11yOverlayBridge.listener = null
        runCatching { overlay.detach() }
        guardConn?.let { runCatching { unbindService(it) } }
        guardConn = null
        // 1.5.3beta2：服务死亡不代表功能关闭；心跳必须保留，否则休眠唤醒/自愈链会断。
        if (instance === this) setInstance(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        /** :guard 通过 Binder 问主进程“无障碍真的连上了吗”，避免 dumpsys 误判 */
        fun isA11yConnected(): Boolean = LsAccessibilityService.isBinderConnected()
    }

    // =====================================================================
    //  配置实时同步
    // =====================================================================
    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        // SP 回调可能在别的线程，统一切回主线程改窗口
        scope.launch { applyConfig(full = false) }
    }

    private fun applyConfig(full: Boolean) {
        val enabled = GesturePrefs.enabled
        // 同步跨进程标记，:guard 守护进程靠它判断该不该继续拉起
        GesturePrefs.writeEnabledFlag(applicationContext)
        recognizer.thresholds = GesturePrefs.thresholds(resources.displayMetrics.density)

        if (enabled) {
            overlay.attach(
                GesturePrefs.edgeLeftDp, GesturePrefs.edgeRightDp, GesturePrefs.edgeBottomDp,
                GesturePrefs.debugEdgeVisible
            )
            overlay.updateWidths(
                GesturePrefs.edgeLeftDp, GesturePrefs.edgeRightDp, GesturePrefs.edgeBottomDp
            )
            overlay.setDebugVisible(GesturePrefs.debugEdgeVisible)
        } else if (overlay.attached) {
            overlay.detach()
        }
        // 三指空调跟着全屏手势服务走；开关变化无需重启服务。
        runCatching { AcFeature.setEnabled(applicationContext, enabled && GesturePrefs.acEnabled) }
        if (full) {
            LsLog.i(TAG, "配置已应用: enabled=$enabled " +
                "边宽 左=${GesturePrefs.edgeLeftDp} 右=${GesturePrefs.edgeRightDp} 底=${GesturePrefs.edgeBottomDp}dp " +
                "灵敏度=${GesturePrefs.sensitivity} 阈值=${recognizer.thresholds}")
        }
    }

    /** 实例侧：重建悬浮条（见 companion.rebuildOverlay 的注释） */
    internal fun rebuildOverlayNow(): String {
        overlay.detach()
        overlay.attach(
            GesturePrefs.edgeLeftDp, GesturePrefs.edgeRightDp, GesturePrefs.edgeBottomDp,
            GesturePrefs.debugEdgeVisible
        )
        LsLog.w(TAG, "重建边缘悬浮条（自愈）")
        return "已重建"
    }

    private fun onGesture(gesture: GestureRecognizer.Gesture) {
        if (!GesturePrefs.enabled || recognizer.blocked) return
        executor.fire(
            when (gesture) {
                GestureRecognizer.Gesture.BACK -> KeyEventExecutor.Action.BACK
                GestureRecognizer.Gesture.HOME -> KeyEventExecutor.Action.HOME
            },
            recognizer.lastDownTime
        )
    }

    // =====================================================================
    //  来自无障碍服务的两路信号
    // =====================================================================
    /** 输入法弹出 → 暂停底部上滑手势，防止与键盘冲突 */
    fun onImeVisible(visible: Boolean) {
        overlay.setBottomEnabled(!visible)
    }

    /** 前台包名变化：桌面/home 时整条全屏手势链路不工作，其他界面照常。 */
    fun onForegroundPackage(pkg: String?) {
        val home = isHomePackage(pkg)
        if (recognizer.blocked == home) return
        recognizer.blocked = home
        overlay.setTouchable(!home)
        LsLog.i(TAG, "前台包=$pkg 全屏手势${if (home) "暂停(桌面)" else "恢复"}")
    }

    private fun isHomePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (pkg in homePackages) return true
        return runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).any { it.activityInfo.packageName == pkg }
        }.getOrDefault(false)
    }

    private val homePackages: Set<String> by lazy {
        val known = setOf(
            "com.dy.launcher",
            "com.byd.launcher",
            "com.android.launcher3",
            "com.android.home"
        )
        val resolved = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        known + resolved
    }

    // =====================================================================
    //  :guard 进程互绑
    // =====================================================================
    private fun bindGuard() {
        guardConn = GuardConnection().also { c ->
            val i = Intent(this, GuardService::class.java)
            runCatching { bindService(i, c, Context.BIND_AUTO_CREATE) }
                .onFailure { LsLog.w(TAG, "绑定 GuardService 失败: ${it.message}") }
        }
    }

    private inner class GuardConnection : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            LsLog.i(TAG, "已绑定 :guard 守护进程")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            LsLog.w(TAG, ":guard 守护进程断开，重新绑定并拉起")
            scope.launch {
                runCatching { GuardService.start(applicationContext) }
                guardConn?.let { runCatching { unbindService(it) } }
                bindGuard()
            }
        }

        override fun onBindingDied(name: android.content.ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    // =====================================================================
    //  通知（Android 10 前台服务必须挂通知，做成 IMPORTANCE_MIN 静默通知）
    // =====================================================================
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.byd.ls.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 1.4.61：通知从"存在证明"变成运维入口 —— 两个按钮，点了只影响我们自己
        val healPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(com.byd.ls.receiver.RestartReceiver.ACTION_HEAL_GESTURE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cardPi = PendingIntent.getBroadcast(
            this, 2,
            Intent(com.byd.ls.receiver.RestartReceiver.ACTION_HEAL_CARD).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, "自愈手势", healPi)
            .addAction(0, "拉卡片", cardPi)
            .build()
    }

    companion object {
        private const val TAG = "GestureSvc"
        private const val NOTIF_ID = 21

        const val ACTION_STOP = "com.byd.ls.action.STOP_GESTURE"
        const val ACTION_APPLY_CONFIG = "com.byd.ls.action.APPLY_CONFIG"

        @Volatile private var instanceRef: GlobalGestureService? = null

        /** 供无障碍服务跨类读取当前服务实例 */
        val instance: GlobalGestureService? get() = instanceRef
        fun setInstance(svc: GlobalGestureService?) { instanceRef = svc }

        fun isRunning(): Boolean = instanceRef != null

        fun start(ctx: Context) {
            val i = Intent(ctx, GlobalGestureService::class.java)
            // 1.4.138：服务已在跑就不要再走 startForegroundService ——
            // 每次调用都会新开一个「5 秒内必须进前台」的倒计时，
            // 心跳/守卫/无障碍多个调用点叠在开机高负载上就是 ANR 的来源。
            if (instanceRef != null) {
                runCatching { ctx.startService(i) }
                return
            }
            runCatching { ctx.startForegroundService(i) }
                .onFailure {
                    LsLog.w("GestureSvc", "startForegroundService 失败，退回 startService: ${it.message}")
                    runCatching { ctx.startService(i) }
                }
        }

        fun stop(ctx: Context) {
            if (!isRunning()) return
            runCatching {
                ctx.startService(
                    Intent(ctx, GlobalGestureService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        fun applyConfigNow(ctx: Context) {
            runCatching {
                ctx.startService(
                    Intent(ctx, GlobalGestureService::class.java).setAction(ACTION_APPLY_CONFIG)
                )
            }
        }

        /**
         * 重建三条边缘悬浮条（掉线的第一层自愈）。
         *
         * 为什么单独要这个入口：无障碍 binder 一断，系统会把 TYPE_ACCESSIBILITY_OVERLAY 的
         * 窗口连带摘掉，但本服务对象还活着——状态显示"运行中"、实际划不动。这时重绑无障碍
         * 太重且有冷却，先 detach + attach 一次最便宜，成功就恢复手势。
         */
        fun rebuildOverlay(): String = runCatching {
            val i = instanceRef ?: return "服务未运行"
            i.rebuildOverlayNow()
        }.getOrElse { "异常：" + (it.message ?: it.javaClass.simpleName) }

        /** 界面读取运行状态 */
        fun statusText(): String {
            val i = instanceRef ?: return "服务未运行"
            return "运行中 · ${i.overlay.stateText()} · 最近判定:${i.recognizer.lastDecision}"
        }

        fun isA11yOverlay(): Boolean = instanceRef?.overlay?.isA11yOverlay() == true

        fun lastCommandText(): String = instanceRef?.executor?.lastCommand ?: "—"
        fun lastBackendText(): String = instanceRef?.executor?.backendText() ?: "—"

        /** 最近一次动作：多久以前、成功与否（health.csv 用） */
        fun lastActionInfo(): String {
            val e = instanceRef?.executor ?: return "无实例"
            val at = e.lastActionAt
            if (at == 0L) return "尚未动作"
            val age = android.os.SystemClock.elapsedRealtime() - at
            val ok = e.lastActionOk?.let { if (it) "ok" else "fail" } ?: "?"
            return age.toString() + "ms/" + ok
        }
    }
}
