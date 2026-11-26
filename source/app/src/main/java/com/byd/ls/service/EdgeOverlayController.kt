package com.byd.ls.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.byd.ls.util.LsLog

/**
 * 三条边缘悬浮窗的管理者。
 *
 * 这是 Android 10 上唯一能拿到全局原始触摸的现实路径：
 * 左/右各一条竖条、底部一条横条，宽度 = 用户在界面设的 10–50dp。
 * 手指落在条内 → 条收到 MotionEvent → 交给 [GestureRecognizer] 判定。
 *
 * 窗口参数说明：
 *  - TYPE_APPLICATION_OVERLAY：API 26+ 唯一可用于服务级悬浮窗的类型，
 *    需要 SYSTEM_ALERT_WINDOW（由 AdbManager 用 `cmd appops set ... allow` 静默授予）
 *  - FLAG_NOT_FOCUSABLE：不抢焦点、不影响下层 App 的输入焦点，也不会弹软键盘
 *  - FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS：铺满整块物理屏，能盖到状态栏/导航栏区域，
 *    底部条才能真正贴在屏幕最下沿
 *  - 不加 FLAG_NOT_TOUCHABLE（那就收不到触摸了）
 *
 * 宽度调整走 updateViewLayout，不重建窗口，避免手势中途出现空档。
 */
class EdgeOverlayController(
    private val ctx: Context,
    private val recognizer: GestureRecognizer,
    initialHost: OverlayHost? = null
) {

    private var host: OverlayHost = initialHost ?: A11yOverlayBridge.appHost(ctx)
    private val wm: WindowManager get() = host.windowManager

    private var leftView: EdgeView? = null
    private var rightView: EdgeView? = null
    private var bottomView: EdgeView? = null

    private var leftLp: WindowManager.LayoutParams? = null
    private var rightLp: WindowManager.LayoutParams? = null
    private var bottomLp: WindowManager.LayoutParams? = null

    @Volatile var attached: Boolean = false
        private set

    @Volatile var bottomEnabled: Boolean = true
        private set

    @Volatile var currentLeftDp: Int = DEFAULT_EDGE_DP
        private set
    @Volatile var currentRightDp: Int = DEFAULT_EDGE_DP
        private set
    @Volatile var currentBottomDp: Int = DEFAULT_EDGE_DP
        private set

    /** 桌面/home 时关掉悬浮条的可触摸标志，让边缘触摸真正落到桌面，不吞点击。 */
    @Volatile private var touchable: Boolean = true

    @Volatile private var debugVisible: Boolean = false
    /** attach 失败后的自动重试计数（避免一次性放弃导致"手势掉了"一直不恢复） */
    private var retryCount = 0

    // =====================================================================
    fun attach(leftDp: Int, rightDp: Int, bottomDp: Int, debug: Boolean) =
        attachInternal(leftDp, rightDp, bottomDp, debug, allowFallback = true)

    private fun attachInternal(
        leftDp: Int, rightDp: Int, bottomDp: Int, debug: Boolean, allowFallback: Boolean
    ) {
        // 无障碍连上后本方法会被 binder 线程回调；addView/removeView 必须在创建 View 的线程
        // （主线程）执行，否则抛 "Only the original thread that created a view hierarchy"。
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post {
                runCatching { attachInternal(leftDp, rightDp, bottomDp, debug, allowFallback) }
            }
            return
        }
        if (attached) { updateWidths(leftDp, rightDp, bottomDp); setDebugVisible(debug); return }
        if (host.requiresPermission && !hasOverlayPermission()) {
            LsLog.e(TAG, "缺少 SYSTEM_ALERT_WINDOW，且无障碍未连接，悬浮条无法创建")
            return
        }
        debugVisible = debug
        currentLeftDp = leftDp.coerceIn(10, 50)
        currentRightDp = rightDp.coerceIn(10, 50)
        currentBottomDp = bottomDp.coerceIn(10, 50)
        val wl = dp(currentLeftDp)
        val wr = dp(currentRightDp)
        val wb = dp(currentBottomDp)

        try {
            leftView = newView(GestureRecognizer.Edge.LEFT).also {
                leftLp = lpFor(GestureRecognizer.Edge.LEFT, wl, MATCH)
                wm.addView(it, leftLp)
            }
            rightView = newView(GestureRecognizer.Edge.RIGHT).also {
                rightLp = lpFor(GestureRecognizer.Edge.RIGHT, wr, MATCH)
                wm.addView(it, rightLp)
            }
            bottomView = newView(GestureRecognizer.Edge.BOTTOM).also {
                bottomLp = lpFor(GestureRecognizer.Edge.BOTTOM, MATCH, wb)
                wm.addView(it, bottomLp)
            }
            attached = true
            applyVisibility()
            LsLog.i(TAG, "悬浮条已创建(${host.label}) 左=$currentLeftDp 右=$currentRightDp 底=$currentBottomDp dp debug=$debug")
        } catch (t: Throwable) {
            LsLog.e(TAG, "创建悬浮条失败(${host.label})", t)
            detach()
            // 无障碍悬浮窗被 ROM 拒绝时，退回应用悬浮窗（需要 SYSTEM_ALERT_WINDOW）
            if (allowFallback && isA11yOverlay() &&
                android.provider.Settings.canDrawOverlays(ctx)
            ) {
                LsLog.w(TAG, "无障碍悬浮窗失败，回退应用悬浮窗")
                host = A11yOverlayBridge.appHost(ctx)
                attachInternal(leftDp, rightDp, bottomDp, debug, allowFallback = false)
            }
        }
        if (!attached && retryCount < 2) {
            retryCount++
            LsLog.w(TAG, "attach 未成功，300ms 后重试第 ${retryCount} 次")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { attachInternal(leftDp, rightDp, bottomDp, debug, allowFallback) }
            }, 300L)
        } else if (attached) {
            retryCount = 0
        }
    }

    /**
     * 切换宿主：无障碍连上/断开时调用。
     * 已挂载则先移除再按新宿主重建，宽度/调试状态保持不变。
     */
    fun setHost(newHost: OverlayHost) {
        if (host.windowType == newHost.windowType &&
            host.windowManager === newHost.windowManager
        ) return
        // 整体投主线程：否则 detach/attach 各自 post，顺序执行时 host 已被改成新的，
        // 会用**新 wm 移除旧 window** → removeView 抛异常、旧窗口残留、新 attach 失败
        //（现场日志："removeView 失败" + 悬浮条未挂载）。
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post { runCatching { setHost(newHost) } }
            return
        }
        val was = attached
        val l = currentLeftDp
        val r = currentRightDp
        val b = currentBottomDp
        val d = debugVisible
        if (was) detach()
        host = newHost
        LsLog.i(TAG, "悬浮窗宿主切换 -> ${newHost.label}")
        if (was) attach(l, r, b, d)
    }

    fun hostLabel(): String = host.label
    /**
     * 手势触发瞬间把三条热区临时扩到全屏，吃掉本次滑动的剩余 MOVE/UP。
     *
     * 依据（docs/11 §13）：边缘条只有 20dp 宽，手指越过 20dp 后的 MOVE 必然落到下层窗口，
     * 系统自带的手势返回会因此再发一次 BACK → 用户看到"一次滑退两层"。
     * 扩到全屏后事件不再漏出；抬手后由 [releaseRest] 恢复原宽度。
     */
    fun grabRest() {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post { runCatching { grabRest() } }
            return
        }
        if (!attached) return
        try {
            leftLp?.let { it.width = MATCH; wm.updateViewLayout(leftView, it) }
            rightLp?.let { it.width = MATCH; wm.updateViewLayout(rightView, it) }
            bottomLp?.let { it.height = MATCH / 2; wm.updateViewLayout(bottomView, it) }
        } catch (t: Throwable) { LsLog.w(TAG, "grabRest 失败: ${t.message}") }
    }

    /** 恢复到配置的边缘宽度 */
    fun releaseRest() {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post { runCatching { releaseRest() } }
            return
        }
        if (!attached) return
        try {
            leftLp?.let { it.width = dp(currentLeftDp); wm.updateViewLayout(leftView, it) }
            rightLp?.let { it.width = dp(currentRightDp); wm.updateViewLayout(rightView, it) }
            bottomLp?.let { it.height = dp(currentBottomDp); wm.updateViewLayout(bottomView, it) }
        } catch (t: Throwable) { LsLog.w(TAG, "releaseRest 失败: ${t.message}") }
    }

    fun isA11yOverlay(): Boolean =
        host.windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun detach() {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post { runCatching { detach() } }
            return
        }
        listOf(
            leftView to leftLp, rightView to rightLp, bottomView to bottomLp
        ).forEach { (v, lp) ->
            if (v != null && lp != null) runCatching { wm.removeViewImmediate(v) }
                .onFailure { LsLog.w(TAG, "removeView 失败: ${it.message}") }
        }
        leftView = null; rightView = null; bottomView = null
        leftLp = null; rightLp = null; bottomLp = null
        attached = false
        LsLog.i(TAG, "悬浮条已移除")
    }

    /** 滑条拖动时实时更新三边宽度，互不影响 */
    fun updateWidths(leftDp: Int, rightDp: Int, bottomDp: Int) {
        currentLeftDp = leftDp.coerceIn(10, 50)
        currentRightDp = rightDp.coerceIn(10, 50)
        currentBottomDp = bottomDp.coerceIn(10, 50)
        if (!attached) return
        val wl = dp(currentLeftDp)
        val wr = dp(currentRightDp)
        val wb = dp(currentBottomDp)
        try {
            leftLp?.let { it.width = wl; wm.updateViewLayout(leftView, it) }
            rightLp?.let { it.width = wr; wm.updateViewLayout(rightView, it) }
            bottomLp?.let { it.height = wb; wm.updateViewLayout(bottomView, it) }
            LsLog.i(TAG, "边缘宽度更新为 左=$currentLeftDp 右=$currentRightDp 底=$currentBottomDp dp")
        } catch (t: Throwable) {
            LsLog.e(TAG, "更新悬浮条宽度失败", t)
        }
    }

    /**
     * 输入法弹出时暂停底部上滑手势（需求明确的防冲突逻辑）。
     * 左右边缘条不受影响 —— 键盘弹出时"返回"依然应该可用。
     */
    fun setBottomEnabled(enabled: Boolean) {
        if (bottomEnabled == enabled) return
        bottomEnabled = enabled
        recognizer.bottomPaused = !enabled
        applyVisibility()
        LsLog.i(TAG, "底部手势 ${if (enabled) "恢复" else "暂停（输入法弹出）"}")
    }

    /**
     * 桌面/home 时整条边缘悬浮窗设为不可触摸。
     * 只让 recognizer 不动作还不够 —— 窗口仍会吃掉桌面边缘的点击；
     * 加 FLAG_NOT_TOUCHABLE 后触摸直接落到 Launcher，手势和点击都不工作。
     */
    fun setTouchable(enabled: Boolean) {
        if (touchable == enabled) return
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() != main) {
            android.os.Handler(main).post { setTouchable(enabled) }
            return
        }
        touchable = enabled
        applyVisibility()
        LsLog.i(TAG, "边缘悬浮条 ${if (enabled) "恢复触摸" else "桌面禁用触摸"}")
    }

    fun setDebugVisible(visible: Boolean) {
        if (debugVisible == visible) return
        debugVisible = visible
        listOf(leftView, rightView, bottomView).forEach {
            it?.setBackgroundColor(if (visible) DEBUG_COLOR else Color.TRANSPARENT)
        }
    }

    /**
     * 让不可见的条真正不接收触摸。
     *
     * 只把 View 设为 INVISIBLE 不够 —— 窗口本身仍然会吃掉触摸。
     * 必须给窗口加/去 FLAG_NOT_TOUCHABLE 才能真正放行给下层 App。
     */
    private fun applyVisibility() {
        val bottomOn = bottomEnabled
        val base = touchable
        applyFlag(leftView, leftLp, base)
        applyFlag(rightView, rightLp, base)
        applyFlag(bottomView, bottomLp, base && bottomOn)
    }

    private fun applyFlag(v: View?, lp: WindowManager.LayoutParams?, touchable: Boolean) {
        if (v == null || lp == null) return
        val target = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (lp.flags != target) {
            lp.flags = target
            runCatching { wm.updateViewLayout(v, lp) }
                .onFailure { LsLog.w(TAG, "切换 NOT_TOUCHABLE 失败: ${it.message}") }
        }
    }

    fun stateText(): String {
        if (!attached) return "未挂载"
        if (!touchable) return "${host.label} · 桌面(暂停)"
        val b = if (!bottomEnabled) "暂停(键盘)" else "工作"
        return "${host.label} · 左右条:工作 · 底部条:$b"
    }

    // =====================================================================
    private fun newView(edge: GestureRecognizer.Edge) =
        EdgeView(host.context, edge, recognizer, debugVisible)

    private fun lpFor(edge: GestureRecognizer.Edge, w: Int, h: Int): WindowManager.LayoutParams {
        val gravity = when (edge) {
            GestureRecognizer.Edge.LEFT -> Gravity.START or Gravity.TOP
            GestureRecognizer.Edge.RIGHT -> Gravity.END or Gravity.TOP
            GestureRecognizer.Edge.BOTTOM -> Gravity.START or Gravity.BOTTOM
        }
        return WindowManager.LayoutParams(
            w, h,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            x = 0
            y = 0
            // 刘海屏也要能铺到边缘（API 28+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // 不进最近任务缩略图、不参与窗口动画
            windowAnimations = 0
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics)
            .toInt().coerceAtLeast(1)

    private fun hasOverlayPermission(): Boolean = when {
        !host.requiresPermission -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            android.provider.Settings.canDrawOverlays(host.context)
        else -> true
    }

    /**
     * 边缘触摸 View。
     *
     * ACTION_DOWN 必须返回 true，否则拿不到后续 MOVE/UP，手势无法判定。
     */
    private class EdgeView(
        context: Context,
        private val edge: GestureRecognizer.Edge,
        private val recognizer: GestureRecognizer,
        debug: Boolean
    ) : View(context) {
        init {
            setBackgroundColor(if (debug) DEBUG_COLOR else Color.TRANSPARENT)
            isClickable = false
            isLongClickable = false
            isFocusable = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            recognizer.onTouch(edge, event)
            return true
        }
    }

    private companion object {
        const val TAG = "EdgeOverlay"
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        const val DEFAULT_EDGE_DP = 20
        /** 调试模式下让边缘条显形，方便用户确认触发范围到底有多大 */
        val DEBUG_COLOR = Color.parseColor("#334C8DFF")
    }
}
