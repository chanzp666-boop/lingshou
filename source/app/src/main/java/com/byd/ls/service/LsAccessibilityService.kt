package com.byd.ls.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.byd.ls.cluster.data.NavState
import com.byd.ls.config.GesturePrefs
import com.byd.ls.receiver.HeartbeatReceiver
import com.byd.ls.util.LsLog

/**
 * 无障碍服务。
 *
 * 它在本方案里只承担两件"普通 App 拿不到"的信息，**不做任何手势注入、不读取屏幕内容**：
 *
 * 1. **输入法可见性**。需求要求"键盘弹出时自动暂停底部上滑手势"。
 *    在 Android 10 上没有干净的替代方案：
 *      · WindowInsets.Type.ime() 要 API 30 才有；
 *      · 边缘悬浮条是 FLAG_NOT_FOCUSABLE 的独立窗口，它的 decorView 根本感知不到 IME insets；
 *      · 从服务里读 getWindowVisibleDisplayFrame 同理无效。
 *    所以只能靠无障碍：开 flagRetrieveInteractiveWindows，在窗口列表里找
 *    type == TYPE_INPUT_METHOD && isVisible。这是 API 29 上唯一可靠的判据。
 *
 * 由 AdbManager 通过 `settings put secure enabled_accessibility_services` 静默开启，
 * 用户也可以手动到 设置 → 无障碍 里勾选。
 */
class LsAccessibilityService : AccessibilityService() {

    /** binder 是否真正连上 system_server。设置开关打开 ≠ 已连接，脱绑后所有无障碍 API 调用都会崩。 */
    @Volatile private var binderConnected = false

    @Volatile private var lastImeVisible: Boolean? = null
    @Volatile private var lastImeScanAt = 0L
    @Volatile private var imePackageHint: String = ""

    private val ensureHandler = Handler(Looper.getMainLooper())
    private val ensureRunnable = object : Runnable {
        override fun run() {
            if (!binderConnected) return
            // 无障碍是系统绑定的锚点：它活着，就保证 FGS/悬浮条也在。
            // FGS 被 ROM 杀掉后最多 10 秒重新拉起；overlay 会按无障碍宿主重建。
            if (GesturePrefs.enabled && !GlobalGestureService.isRunning()) {
                LsLog.w(TAG, "无障碍存活但手势服务不在，重新拉起 FGS")
                GlobalGestureService.start(this@LsAccessibilityService)
            }
            ensureHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LsLog.i(TAG, "onCreate：系统已创建无障碍服务（pid=${android.os.Process.myPid()}）")
    }

    fun softReset(): Boolean = runCatching { disableSelf(); true }.getOrDefault(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        binderConnected = true
        setInstance(this)
        LsLog.i(TAG, "onServiceConnected：无障碍服务已被系统真正 bind（Binding 成功）")
        // 【1.4.12】真正绑上就清掉「已知不支持」标记：
        // 该标记的语义是「当前无法绑定」，绑定成功即失效；否则下次半绑时自愈会被误跳过。
        runCatching { A11yHealer.clearUnsupported(this) }
        // 代码里再补一次 serviceInfo，确保厂商 ROM 没把 xml 里的 flags 吃掉
        if (!EXP_NO_SERVICE_INFO_TUNING) try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            // 1.4.91：事件口径对齐迪加（只订阅窗口状态变化，迪加 0x20）；
            // 与 res/xml/accessibility_service_config.xml 必须保持一致——这里会覆盖 xml。
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100
            // canRetrieveWindowContent 已在 xml 中设为 true：getWindows() 在 Android 10
            // 上需要该能力，否则窗口列表为空/异常，表现为「已勾选但未绑定」。
            // 本服务只读窗口类型与包名，不读取任何窗口内容。
            serviceInfo = info
        } catch (t: Throwable) {
            LsLog.w(TAG, "setServiceInfo 失败，沿用 xml 配置: ${t.message}")
        }

        imePackageHint = readDefaultImePackage()
        LsLog.i(TAG, "无障碍服务已连接，当前默认输入法=$imePackageHint")

        // GKD 同款锚点：无障碍连上 → 通知 FGS 切到 TYPE_ACCESSIBILITY_OVERLAY，
        // 并确保 FGS/Job/心跳在跑。之后即使 ADB 通道断了，手势也能用。
        A11yOverlayBridge.onConnected(this)
        A11yHealer.onA11yConnected(this)
        A11yKeepAliveOverlay.attach(this)
        // 用户关掉总开关后，不能因为无障碍重绑又把 FGS 拉起来
        if (GesturePrefs.enabled) GlobalGestureService.start(this)
        BootJobService.schedule(this)
        HeartbeatReceiver.schedule(this)
        ensureHandler.removeCallbacks(ensureRunnable)
        ensureHandler.postDelayed(ensureRunnable, 5_000L)
        // 无障碍 bind 比开机广播更早到，借这个锚点提前拉一次仪表卡片；
        // 延迟 3.5s 是为了越过 ClusterCardKeeper 的 BORN_GUARD_MS 抢跑保护。
        ensureHandler.postDelayed({
            runCatching {
                if (GesturePrefs.clusterEnabled) ClusterCardKeeper.heal(this, "a11y")
            }
        }, 3_500L)

        // 连上后立刻扫一次，避免"服务晚于键盘启动"导致状态错
        updateImeState(force = true)
        pendingImeVisible?.let { dispatchIme(it) }
        // 服务实例可能还没创建好，稍后再补一次前台包名；桌面启动时不会漏掉 home 约束。
        ensureHandler.postDelayed({
            runCatching {
                GlobalGestureService.instance?.onForegroundPackage(
                    rootInActiveWindow?.packageName?.toString()
                )
            }
        }, 1_000L)
    }

    /** 1.4.140：CONTENT_CHANGED 的 300ms 闸门（地图渲染时事件很密）。 */
    private var lastNavContentAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 脱绑状态下直接丢弃事件，不触碰任何无障碍 API（binder 已死，调用即崩）
        if (!binderConnected) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                GlobalGestureService.instance?.onForegroundPackage(pkg)
                // 1.4.117：导航 App 自己的窗口一变就提速采样（进入导航 5~10s → 约 2s）。
                // 只动节拍不动判定；事件不来就什么都不发生，行为与 1.4.116 一致。
                // 1.4.140：带上窗口文本 —— 命中导航特征串就直接定论，不再只提速
                if (NavState.isNavPackage(pkg)) {
                    NavState.onNavWindow(
                        pkg,
                        event.text?.joinToString(" ") { it.toString() },
                        event.contentDescription?.toString()
                    )
                }
                // 输入法自己的窗口状态变化 → 键盘弹出/收起
                if (isImePackage(pkg)) {
                    updateImeState(force = true)
                    return
                }
                updateImeState(force = false)
            }

            // 1.4.140：导航中高德持续刷新剩余距离/时间，发的是 CONTENT_CHANGED 而不是 STATE_CHANGED。
            // 只听 STATE_CHANGED 的话，「导航中」这个证据在 20 秒事件窗之后就会过期，
            // 像素重新拿回否决权 —— 正好落在高德重绘误翻的窗口里。这里把内容变化也接进来。
            // 300ms 闸门：地图渲染时这类事件很密，先挡一道再进文本匹配。
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (!NavState.isNavPackage(pkg)) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastNavContentAt < 300L) return
                lastNavContentAt = now
                NavState.onNavWindow(
                    pkg,
                    event.text?.joinToString(" ") { it.toString() },
                    null
                )
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> updateImeState(force = false)
        }
    }

    override fun onInterrupt() {
        com.byd.ls.util.LsLog.e("A11ySvc", "A11Y_INTERRUPT") /* 无反馈输出，无需处理 */ }


    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 1.4.83：诊断 —— 半绑多半是运行中被解绑，这里记下是谁断的
        com.byd.ls.util.LsLog.e("A11ySvc", "A11Y_UNBIND intent=" + intent)
        android.util.Log.w("A11ySvc", "A11Y_UNBIND " + intent)
        binderConnected = false
        ensureHandler.removeCallbacks(ensureRunnable)
        A11yKeepAliveOverlay.detach()
        A11yOverlayBridge.onDisconnected(this)
        LsLog.w(TAG, "无障碍服务被解绑（可能被系统关闭），已回退应用悬浮窗/等待系统重绑")
        if (instance === this) setInstance(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        binderConnected = false
        ensureHandler.removeCallbacks(ensureRunnable)
        A11yKeepAliveOverlay.detach()
        A11yOverlayBridge.onDisconnected(this)
        if (instance === this) setInstance(null)
        super.onDestroy()
    }

    // =====================================================================
    //  输入法可见性
    // =====================================================================
    private fun updateImeState(force: Boolean) {
        if (!binderConnected) return
        val now = System.currentTimeMillis()
        // 非强制时做 120ms 节流，避免窗口频繁变化时反复扫描
        if (!force && now - lastImeScanAt < 120) return
        lastImeScanAt = now

        val visible = scanImeWindow() ?: return   // 扫描失败时保持原判断，不乱改
        if (lastImeVisible != visible) {
            lastImeVisible = visible
            LsLog.i(TAG, "输入法可见性变化 → $visible")
            dispatchIme(visible)
        }
    }

    /** 在交互窗口列表里找输入法窗口；返回 null 表示这次扫描不可信 */
    private fun scanImeWindow(): Boolean? {
        return try {
            val list = windows ?: return null
            if (list.isEmpty()) return null
            var found = false
            try {
                for (w in list) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        // AccessibilityWindowInfo 无 isVisible() 方法；isActive() 表示当前处于激活状态，
                        // 再叠加窗口实际高度 > 0，才算键盘真的显示着
                        val rect = android.graphics.Rect()
                        w.getBoundsInScreen(rect)
                        if (w.isActive && rect.height() > 0) found = true
                    }
                }
            } finally {
                list.forEach { runCatching { it.recycle() } }
            }
            found
        } catch (t: Throwable) {
            LsLog.w(TAG, "扫描输入法窗口失败: ${t.message}")
            null
        }
    }

    private fun dispatchIme(visible: Boolean) {
        pendingImeVisible = visible
        GlobalGestureService.instance?.onImeVisible(visible)
    }

    // =====================================================================
    private fun isImePackage(pkg: String): Boolean {
        if (imePackageHint.isBlank()) imePackageHint = readDefaultImePackage()
        return imePackageHint.isNotBlank() && pkg == imePackageHint
    }

    private fun readDefaultImePackage(): String = try {
        val v = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        v.substringBefore('/').trim()
    } catch (t: Throwable) { "" }

    /**
     * 按屏幕坐标点一下（点穿用）。
     *
     * 为什么优先走无障碍而不是 `input tap`：`input` 每次都要 fork 一个 app_process，
     * 实测 60–120ms；无障碍点击是进程内 binder 调用，5–20ms，而且能精确落到最内层可点控件。
     * 找不到可点节点再退回 shell 注入。
     */
    fun clickAt(x: Float, y: Float, longClick: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findClickableAt(root, x, y) ?: return false
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK
        else AccessibilityNodeInfo.ACTION_CLICK
        val ok = runCatching { target.performAction(action) }.getOrDefault(false)
        LsLog.i(TAG, "坐标点击(${if (longClick) "长按" else "点按"}) ${x.toInt()},${y.toInt()} -> $ok")
        return ok
    }

    /** 深度优先找出包含该坐标、且可点击的最小节点（最内层就是用户看到的那个按钮） */
    private fun findClickableAt(node: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x.toInt(), y.toInt())) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = findClickableAt(child, x, y) ?: continue
            val cr = android.graphics.Rect(); hit.getBoundsInScreen(cr)
            val area = cr.width() * cr.height()
            if (area in 1 until bestArea) { best = hit; bestArea = area }
        }
        if (best != null) return best
        val selfClickable = node.isClickable || node.isCheckable || node.isLongClickable
        return if (selfClickable) node else null
    }


    companion object {
        private const val TAG = "A11y"

        /**
         * 【1.4.6 · H2 判定实验开关（已判定完毕，保持 false）】
         * true  = 不在代码里补 serviceInfo，完全用 res/xml 的配置（实验用）。
         * false = 正常行为：代码补 eventTypes/flags/notificationTimeout。
         *
         * 判定结论见 docs/08 §3：精简配置在标准 AOSP 上可绑、车机仍悬挂 → H2 排除、H1 成立。
         * 故恢复 false 与完整 xml；本开关仅留作将来复现实验。
         */
        private const val EXP_NO_SERVICE_INFO_TUNING = false

        @Volatile private var instanceRef: LsAccessibilityService? = null
        val instance: LsAccessibilityService? get() = instanceRef
        fun setInstance(svc: LsAccessibilityService?) { instanceRef = svc }

        /** 手势服务尚未启动时先缓存，服务起来后补一次同步 */
        @Volatile var pendingImeVisible: Boolean? = null


        /** binder 真实绑定状态（脱绑巡检用），比 Settings 里的开关标记可靠 */
        fun isBinderConnected(): Boolean = instanceRef?.binderConnected == true

    }
}
