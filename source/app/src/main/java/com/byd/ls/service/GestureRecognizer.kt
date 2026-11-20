package com.byd.ls.service

import android.view.MotionEvent
import com.byd.ls.config.GesturePrefs
import com.byd.ls.util.LsLog
import kotlin.math.abs

/**
 * 手势判定器。
 *
 * 三条边缘各自独立判定，只有两类动作：
 *
 *  左边缘 / 右边缘
 *    ACTION_DOWN 记起点 → 水平位移超过 triggerPx，且 |dx| > 2·|dy|，方向朝屏幕内侧
 *    → 触发 BACK。一次按下只触发一次（triggered 标志位）。
 *
 *  底部边缘
 *    抬手时纵向位移足够（-dy > triggerPx）→ HOME。
 *    不区分速度、不判定停留，没有最近任务分支。
 *
 * 阈值由灵敏度滑条经 [GesturePrefs.thresholds] 映射而来，改动即时生效。
 *
 * ⚠️ 已知代价（设计阶段已向用户说明）：落在边缘条内的触摸会被悬浮窗吃掉，
 * 无法透传给下层 App —— Android 没有触摸透传 API。未达阈值就抬手时，这次触摸
 * 对下层应用同样是丢失的。缓解手段是把条宽压到 10dp。
 */
class GestureRecognizer(
    private val density: Float,
    private val onGesture: (Gesture) -> Unit,
    /**
     * 落在边缘条里的「点按」：条吃掉了触摸，上层按钮就点不动。
     * 这里把点击回放回原坐标（input tap），按钮照常响应。
     */
    private val onTapAt: (Float, Float) -> Unit = { _, _ -> },
    /** 贴边长按：同样要回放，否则条内的长按操作全废 */
    private val onLongPressAt: (Float, Float) -> Unit = { _, _ -> }
) {

    enum class Edge { LEFT, RIGHT, BOTTOM }

    /** 判定结果，直接对应要执行的系统动作 */
    enum class Gesture { BACK, HOME }

    @Volatile var thresholds: GesturePrefs.Thresholds = GesturePrefs.Thresholds(40f * density)

    /** 底部条是否被暂停（输入法弹出时置 true） */
    @Volatile var bottomPaused: Boolean = false

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var maxMove = 0f
    private var triggered = false

    /**
     * 1.4.15：一次手势结束后 [_SUPPRESS_MS] 内忽略新的触摸序列。
     *
     * 目的：同一次实体滑动被系统拆成「DOWN…UP」「DOWN…UP」两段时，第二段不再触发
     * 第二次返回（现场：滑一次，个别 App 直接退出）。只拦手势识别；被抑制的那一段
     * 连点穿补 tap 也一并跳过——宁可漏补一次点按，也不要 App 多退一层。
     */
    private var suppressUntil = 0L
    private var paused = false
    private val _SUPPRESS_MS = 320L

    /** 1.4.80：本次手势的 downTime —— 同一手势只允许执行一次动作 */
    @Volatile var lastDownTime: Long = 0L

    /** 点按判定：位移小于它、且用时小于 TAP_MAX_MS 才算"点" */
    private val tapSlopPx: Float get() = 12f * density

    /**
     * 点穿重放的自抑制窗口。
     *
     * `input tap` 注入的触摸会**再次落到我们的边缘条上**，如果不设窗口就会
     * 自己触发自己 → 无限连点（现场实测：一次点击连发 6 次 input tap）。
     */
    @Volatile private var replayGuardUntil = 0L
    @Volatile private var lastReplayX = -9999f
    @Volatile private var lastReplayY = -9999f

    /** 供调试/界面显示最近一次判定结果 */
    @Volatile var lastDecision: String = "—"
        private set

    /** 手势触发时通知外层扩全屏；抬手后由外层恢复（见 EdgeOverlayController.grabRest/releaseRest） */
    var onGestureGrab: (() -> Unit)? = null
    var onGestureRelease: (() -> Unit)? = null

    fun onTouch(edge: Edge, ev: MotionEvent) {
        if (edge == Edge.BOTTOM && bottomPaused) return

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownTime = ev.downTime
                if (System.currentTimeMillis() < suppressUntil) { paused = true; return }
                paused = false
                downX = ev.rawX
                downY = ev.rawY
                downAt = ev.eventTime
                maxMove = 0f
                triggered = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                maxMove = maxOf(maxMove, kotlin.math.hypot(dx, dy))
                when (edge) {
                    Edge.LEFT -> if (!triggered && isBackSwipe(dx, dy, inwardPositive = true)) {
                        triggered = true
                        fire(Gesture.BACK, "左边缘右滑 dx=${dx.toInt()}px 阈值=${thresholds.triggerPx.toInt()}")
                    } else if (!triggered && isFastBackSwipe(dx, dy, ev.eventTime, inwardPositive = true)) {
                        // 主流水准的跟手：快速轻扫即使没过距离阈值也立刻返回（速度优先）
                        triggered = true
                        fire(Gesture.BACK, "左边缘快扫 dx=${dx.toInt()}px 用时=${ev.eventTime - downAt}ms")
                    }
                    Edge.RIGHT -> if (!triggered && isBackSwipe(dx, dy, inwardPositive = false)) {
                        triggered = true
                        fire(Gesture.BACK, "右边缘左滑 dx=${dx.toInt()}px 阈值=${thresholds.triggerPx.toInt()}")
                    } else if (!triggered && isFastBackSwipe(dx, dy, ev.eventTime, inwardPositive = false)) {
                        triggered = true
                        fire(Gesture.BACK, "右边缘快扫 dx=${dx.toInt()}px 用时=${ev.eventTime - downAt}ms")
                    }
                    Edge.BOTTOM -> {
                        // 上滑越过阈值即触发，不等抬手：更跟手
                        val dyUp = ev.rawY - downY
                        if (!triggered && -dyUp > thresholds.triggerPx) {
                            triggered = true
                            fire(Gesture.HOME,
                                "底部上滑 dy=${dyUp.toInt()}px 阈值=${thresholds.triggerPx.toInt()}")
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                // MOVE 采样可能刚好错过阈值（快速轻扫），抬手时再补判一次
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!triggered) {
                    when (edge) {
                        Edge.LEFT -> if (isBackSwipe(dx, dy, inwardPositive = true)) {
                            triggered = true
                            fire(Gesture.BACK, "左边缘右滑(UP) dx=${dx.toInt()}px")
                        } else if (isFastBackSwipe(dx, dy, ev.eventTime, inwardPositive = true)) {
                            // 极快的轻扫常常只有 DOWN/UP 两个事件，MOVE 一次都不给，这里必须再判一次
                            triggered = true
                            fire(Gesture.BACK, "左边缘快扫(UP) dx=${dx.toInt()}px 用时=${ev.eventTime - downAt}ms")
                        }
                        Edge.RIGHT -> if (isBackSwipe(dx, dy, inwardPositive = false)) {
                            triggered = true
                            fire(Gesture.BACK, "右边缘左滑(UP) dx=${dx.toInt()}px")
                        } else if (isFastBackSwipe(dx, dy, ev.eventTime, inwardPositive = false)) {
                            triggered = true
                            fire(Gesture.BACK, "右边缘快扫(UP) dx=${dx.toInt()}px 用时=${ev.eventTime - downAt}ms")
                        }
                        Edge.BOTTOM -> if (-dy > thresholds.triggerPx) {
                            triggered = true
                            fire(Gesture.HOME, "底部上滑(UP) dy=${dy.toInt()}px")
                        }
                    }
                    // 既没触发手势、也没怎么移动、又很快抬手 → 这是一次"点按"。
                    // 边缘条把这次点击吃掉了，必须替用户在原坐标补一次 input tap，
                    // 否则贴着边缘的按钮永远点不动。
                    val dt = ev.eventTime - downAt
                    val now = System.currentTimeMillis()
                    // 只在「同一坐标附近 + 时间窗内」才认为是自己注入的回声。
                    // 之前用纯时间窗，用户快速点第二个按钮会被误吞（反馈就是"点不动"）。
                    val isEcho = now <= replayGuardUntil &&
                        kotlin.math.abs(downX - lastReplayX) < 24f * density &&
                        kotlin.math.abs(downY - lastReplayY) < 24f * density
                    if (!triggered && maxMove < tapSlopPx && !isEcho) {
                        replayGuardUntil = now + TAP_REPLAY_GUARD_MS
                        lastReplayX = downX
                        lastReplayY = downY
                        if (dt >= LONG_PRESS_MS) {
                            // 贴边长按也要能补：否则条内的长按菜单/持续操作全废
                            onLongPressAt(downX, downY)
                        } else if (dt < TAP_MAX_MS) {
                            onTapAt(downX, downY)
                        }
                    }
                }
                // 正常抬手：同样进入抑制窗（防止同一滑动被系统拆成第二段再退一次）
                runCatching { onGestureRelease?.invoke() }
                if (!paused) suppressUntil = System.currentTimeMillis() + _SUPPRESS_MS
                paused = false
                triggered = false
            }

            MotionEvent.ACTION_CANCEL -> {
                // 系统取消触摸不是用户抬手，不能补判触发 BACK/HOME
                triggered = false
                if (!paused) suppressUntil = System.currentTimeMillis() + _SUPPRESS_MS
                paused = false
            }
        }
    }

    /**
     * 返回手势判定：朝屏幕内侧、水平位移过阈值、且水平明显主导。
     *
     * 1.1.1 把主导比从 2.0 放宽到 1.25：原来斜着划（水平 40dp、垂直 25dp）直接不触发，
     * 手感上就是"滑了没反应"。
     */
    private fun isBackSwipe(dx: Float, dy: Float, inwardPositive: Boolean): Boolean {
        val inward = if (inwardPositive) dx else -dx
        return inward > thresholds.triggerPx && abs(dx) > abs(dy) * HORIZONTAL_DOMINANCE
    }

    private fun fire(gesture: Gesture, why: String) {
        lastDecision = why
        // 立刻扩全屏吃掉剩余事件（防系统手势再补一次）
        runCatching { onGestureGrab?.invoke() }
        LsLog.d(TAG, "手势触发: $why → $gesture")
        onGesture(gesture)
    }

    /**
     * 快扫判定：位移只到阈值的 60%，但速度够快也算返回。
     * 主流手机的返回手势是"速度+距离"双条件，只卡距离会显得钝。
     */
    private fun isFastBackSwipe(dx: Float, dy: Float, at: Long, inwardPositive: Boolean): Boolean {
        val ax = if (inwardPositive) dx else -dx
        if (ax <= thresholds.triggerPx * 0.55f) return false
        if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * HORIZONTAL_DOMINANCE) return false
        val dt = (at - downAt).coerceAtLeast(1L)
        val speed = ax / dt          // px/ms
        return speed >= FAST_SWIPE_PX_PER_MS
    }

    private companion object {
        const val TAG = "Gesture"
        /**
         * 快扫阈值 0.6px/ms（240dpi ≈ 0.4dp/ms）。
         * 真人手指"轻快一划"典型是 0.7–1.5px/ms；设 1.2 会把很多正常快划判成"太慢"，
         * 表现就是"划了没反应"。距离门槛同时降到阈值的 55%，两条一起放宽才跟手。
         */
        const val FAST_SWIPE_PX_PER_MS = 0.6f
        /** 超过这个时长按住没动 = 长按，回来要补长按而不是点击 */
        const val LONG_PRESS_MS = 500L
        /** 水平位移要明显大于垂直，否则斜划也会触发返回 */
        const val HORIZONTAL_DOMINANCE = 1.25f
        /** 点按的最长按压时间：超过就当长按，不补点击（长按语义交给上层 App 自己） */
        const val TAP_MAX_MS = 300L
        /** 点穿重放后多久内不再补点：覆盖 injection 自身回调进来的那次触摸 */
        const val TAP_REPLAY_GUARD_MS = 600L
    }
}
