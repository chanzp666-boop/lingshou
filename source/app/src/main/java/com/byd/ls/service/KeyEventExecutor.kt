package com.byd.ls.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 按键执行层（1.1.1 起改为「无障碍优先」）。
 *
 * 为什么改：
 *  `input keyevent` 每次都要 cold start 一个 app_process，实测 200–400ms，
 *  这段开销由 input 本身带来，换任何执行方式都省不掉。
 *  `AccessibilityService.performGlobalAction()` 是进程内 binder 调用，毫秒级返回，
 *  而且不需要 shell(UID 2000) 权限，ADB 断了也照样能用。
 *
 * 分层策略：
 *  1. 无障碍已 bind → performGlobalAction(BACK/HOME)，成功即结束；
 *  2. 未 bind / 调用返回 false → 一次性 exec 写 `input keyevent`；
 *  4. 全部失败 → 记录明确错误，界面显示实际链路，不静默吞掉。
 *
 * HOME 仍用 GLOBAL_ACTION_HOME / keyevent 3，语义是「回系统当前默认桌面」，
 * 默认桌面设成迪友就回迪友，设成原车桌面就回原车桌面。
 */
class KeyEventExecutor(private val appCtx: Context, private val scope: CoroutineScope) {

    enum class Action { BACK, HOME }

    private val main = Handler(Looper.getMainLooper())
    private val lastFireAt = LongArray(2)

    /** 供界面显示"上一次执行的命令"，方便现场排查权限问题 */
    @Volatile var lastCommand: String = "—"
        private set

    @Volatile var lastError: String = ""
        private set

    /** 上一次真正生效的链路：无障碍 / 一次性exec / 失败 */
    @Volatile var lastBackend: String = "—"
        private set

    /** 从手势触发到动作下发的耗时（毫秒），-1 表示未测到 */
    @Volatile var lastLatencyMs: Long = -1L
    /** 最近一次真正下发动作的时刻（elapsedRealtime）：判断"掉线那一刻还能不能动手" */
    @Volatile var lastActionAt: Long = 0L
    /** 最近一次动作是否成功（null=还没执行过） */
    @Volatile var lastActionOk: Boolean? = null
        private set

    /** 1.4.80：token = 本次手势的 downTime；同一手势只执行一次，彻底治「滑一次退两层」 */
    @Volatile private var lastToken: Long = 0L

    fun fire(action: Action, token: Long = 0L) {
        if (token != 0L && token == lastToken) {
            LsLog.d(TAG, "同手势重复触发，丢弃 $action")
            return
        }
        if (token != 0L) lastToken = token
        val now = System.currentTimeMillis()
        if (now - lastFireAt[action.ordinal] < DEBOUNCE_MS) {
            LsLog.d(TAG, "去抖动丢弃 $action")
            return
        }
        lastFireAt[action.ordinal] = now

        val started = SystemClock.uptimeMillis()
        val svc = LsAccessibilityService.instance
        // 1.4.80：不再以 isBinderConnected() 为门槛——半绑时该标记是陈旧的，先直接试一次
        if (svc != null) {
            val gid = when (action) {
                Action.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                Action.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            }
            // performGlobalAction 要求在主线程调用；手势事件本身就在主线程，直接执行不额外排队
            val task = Runnable {
                val ok = runCatching { svc.performGlobalAction(gid) }.getOrDefault(false)
                if (ok) {
                    lastBackend = "无障碍"
                    lastLatencyMs = SystemClock.uptimeMillis() - started
                    lastCommand = "performGlobalAction($gid)"
                    lastActionAt = android.os.SystemClock.elapsedRealtime()
                    lastActionOk = true
                    lastError = ""
                    LsLog.i(TAG, "[$action] 无障碍执行成功，耗时 ${lastLatencyMs}ms")
                } else {
                    lastActionAt = android.os.SystemClock.elapsedRealtime()
            lastActionOk = false
            LsLog.w(TAG, "[$action] performGlobalAction 返回 false，降级 shell")
                    execViaShell(action, started)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
            return
        }

        execViaShell(action, started)
    }

    /**
     * 「点穿」：边缘条吃掉了触摸，落在条里的**点按**要替用户补一次。
     *
     * Android 没有触摸透传 API，悬浮窗一旦接收就吃掉。可用的办法是把点击事件
     * 在条内重放一次（input tap），按钮照常响应；代价只有几十毫秒（只 fork 一次 input）。
     */
    fun tapAt(x: Float, y: Float, longClick: Boolean = false) {
        // 快路：无障碍进程内点击（5–20ms），失败再退回 input 注入（60–120ms）
        val svc = LsAccessibilityService.instance
        if (svc != null && LsAccessibilityService.isBinderConnected()) {
            val ok = runCatching { svc.clickAt(x, y, longClick) }.getOrDefault(false)
            if (ok) {
                lastBackend = "点穿(无障碍)"
                lastCommand = "a11y.${if (longClick) "longClick" else "click"}(${x.toInt()},${y.toInt()})"
                lastError = ""
                return
            }
        }
        val cmd = if (longClick) "input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 800"
        else "input tap ${x.toInt()} ${y.toInt()}"
        lastCommand = cmd
        scope.launch(Dispatchers.IO) {
            val r = ShellManager.exec(appCtx, cmd, 3_000L)
            if (r.success) {
                lastBackend = "点穿重放"
                LsLog.i(TAG, "[TapReplay] $cmd ok")
            } else {
                lastError = r.stdout.take(120).ifBlank { "exit=${r.exitCode}" }
                LsLog.w(TAG, "[TapReplay] $cmd failed: $lastError")
            }
        }
    }

    private fun execViaShell(action: Action, started: Long) {
        // 已经探测过、且明确没有特权通道时快速失败，避免每次手势都触发一次 3s 重探
        // local 缓存可能是 5s 前的旧结果，继续走 ShellManager.exec 触发一次重探；
        // 只有明确是 ADB/Helper 但当前不可用时才快速失败。
        val channelId = ShellManager.currentChannelId()
        if (channelId != "none" && channelId != "local" && !ShellManager.isPrivileged()) {
            lastBackend = "失败"
            lastError = "无障碍未绑定，且当前通道无特权（${ShellManager.currentChannelName()}）"
            LsLog.w(TAG, lastError)
            return
        }
        val cmd = when (action) {
            Action.BACK -> "input keyevent 4"
            Action.HOME -> "input keyevent 3"
        }
        lastCommand = cmd
        scope.launch(Dispatchers.IO) {
            // 1.2.23 起没有常驻 shell：备用链路直接一次性 exec。
            // 这条路径只在无障碍未绑定/返回 false 时才会走到（正常情况永远是 2–5ms 的无障碍），
            // 多一次 adblib 握手（~100ms）换「adbd 上零常驻连接」，值得。
            val r = ShellManager.exec(appCtx, cmd, 6_000)
            if (r.success) {
                lastBackend = "一次性exec"
                lastLatencyMs = SystemClock.uptimeMillis() - started
                lastError = ""
                LsLog.i(TAG, "[$action] 经一次性 exec 执行: $cmd，耗时 ${lastLatencyMs}ms")
            } else {
                lastBackend = "失败"
                lastError = "exit=${r.exitCode} ${r.stdout.take(200)}"
                LsLog.e(TAG, "[$action] 执行失败 $cmd → $lastError")
            }
        }
    }


    fun backendText(): String = when {
        lastLatencyMs < 0 -> lastBackend
        else -> "$lastBackend（${lastLatencyMs}ms）"
    }

    private companion object {
        const val TAG = "KeyEvent"
        // 120ms：足够挡住边缘抖动的重复触发，又不会吞掉用户有意的连续返回
        /**
         * 同类动作最小间隔。
         *
         * 1.4.15：120ms → 450ms。现场反馈「左右滑一次，有的 App 直接退出」：
         * 一次滑动被拆成两个触摸序列（窗口重建 / 系统手势并存 / 降级注入被边缘条回收），
         * 每段各自触发一次 BACK，App 就被连退两层。120ms 拦不住 200–400ms 的第二发。
         * 450ms 覆盖人手一次滑动的总时长，又短于两次有意的连续返回（通常 >600ms）。
         */
        const val DEBOUNCE_MS = 450L
    }
}
