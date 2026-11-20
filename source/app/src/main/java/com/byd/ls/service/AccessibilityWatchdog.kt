package com.byd.ls.service

import android.content.Context
import com.byd.ls.config.GesturePrefs
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍周期巡检。
 *
 * 只负责「定时触发」，具体读写策略全部收敛在 [A11yHealer] 里：
 * read-modify-write、直写/降级、定向 rebind、日志留痕。
 *
 * 1.2.22 降 CPU：
 *  · 周期从 60s 放宽到 90s —— 稳态下 [A11yHealer] 会在最前面 3 行就短路返回，
 *    但要醒一次、读两次 settings；90s 只是让"被 ROM 回写"的最坏窗口从 60s 变 90s，
 *    而 observer 那条路（0.8s 内补回）才是真正兜底的，周期巡检只是最后一道。
 *  · adblib 空闲回收合并到同一个循环里（原来 60s 一轮两件事，现在 90s 一轮两件事）。
 *  · 关掉总开关时直接不进循环，不空转。
 */
object AccessibilityWatchdog {

    private const val TAG = "A11yWatchdog"
    private const val CHECK_INTERVAL_MS = 60_000L   // 1.4.87：60s，与迪加同节奏
    private const val HALF_STREAK_NEED = 3        // 连续 3 次（≈3 分钟）才判定半绑
    private var halfStreak = 0

    /**
     * 1.4.92：连续 [QUIET_AFTER_FAILS] 轮自愈都救不回来 → 静默 [QUIET_CYCLES] 个周期。
     *
     * 依据（docs/13 §10）：本车休眠唤醒后半绑，disableSelf / remove-add 两种手段全无效；
     * 继续每 60 秒打一次只会反复占用 adblib 槽位（连两条 shell.exec 就 exit=-1）。
     * 静默 = 不再消耗通道，等下一次唤醒（新进程首发 bind 成功率高）或用户手动点一下。
     */
    private const val QUIET_AFTER_FAILS = 3
    private const val QUIET_CYCLES = 10
    private var failStreak = 0
    private var quietLeft = 0

    fun start(ctx: Context, scope: CoroutineScope) {
        val appCtx = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                // 用户关掉手势后不要再醒来（省电，也避免把服务拉起来）
                if (!GesturePrefs.enabled) continue
                // 1.4.87 精简版（对齐迪加）：
                //  · 连续 3 次采样都是「dumpsys 已绑 + 进程 binder 未连」才动手
                //  · 三个动作：disableSelf → 名单只加不删 → RESTART 广播
                //  · **不再自动重装、不再 force-stop**（那两条会打断刚建立的绑定）
                if (quietLeft > 0) {
                    // 1.4.92：本轮已判定「救不回来」，静默期间只做 adb 空闲回收，不再打自愈
                    quietLeft--
                    LsLog.i(TAG, "A11Y_QUIET 静默中（剩 " + quietLeft + " 个周期）")
                    runCatching { ShellManager.adbLibChannel().reapIfIdle() }
                        .onFailure { LsLog.w(TAG, "adb 空闲回收异常: " + it.message) }
                    continue
                }
                if (LsAccessibilityService.isBinderConnected()) {
                    halfStreak = 0
                    failStreak = 0
                } else if (++halfStreak >= HALF_STREAK_NEED) {
                    halfStreak = 0
                    val ok = LsAccessibilityService.instance?.softReset() ?: false
                    LsLog.e(TAG, "A11Y_SOFT_RESET ok=" + ok)
                    if (ok) delay(4_000L)
                    var back = LsAccessibilityService.isBinderConnected()
                    LsLog.e(TAG, "A11Y_SOFT_RESET rebind=" + back)
                    if (back) {
                        A11yHealer.clearUnsupported(appCtx)
                    } else {
                        A11yHealer.rebindRemoveAdd(appCtx)
                        delay(3_000L)
                        back = LsAccessibilityService.isBinderConnected()
                        LsLog.e(TAG, "A11Y_REBIND removeAdd=" + back)
                    }
                    if (!back) {
                        failStreak++
                        if (failStreak >= QUIET_AFTER_FAILS) {
                            quietLeft = QUIET_CYCLES
                            failStreak = 0
                            LsLog.w(TAG, "A11Y_QUIET 连续自愈无果 → 静默 " + QUIET_CYCLES + " 个周期（1.4.92，不再赌 AMS）")
                        }
                    } else {
                        failStreak = 0
                    }
                }

                // 1.4.87：旧的双重自愈（软重置 + 自动重装）已删除，只保留上面的三动作
                runCatching { ShellManager.adbLibChannel().reapIfIdle() }
                    .onFailure { LsLog.w(TAG, "adb 空闲回收异常: ${it.message}") }
            }
        }
    }
}
