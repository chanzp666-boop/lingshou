package com.byd.ls.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.byd.ls.config.GesturePrefs
import com.byd.ls.guard.DaemonGuard
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.shell.AdbAutoAuth
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AlarmManager 心跳兜底。
 *
 * 为什么还需要它：NDK 守护进程跑在 App 自己的 UID 下，如果比亚迪的"应用加速管理"
 * 是按 UID 对整个进程组下 kill -9，Java 的两个 Service 和 native daemon 会同时死掉，
 * 谁也拉不起谁。AlarmManager 由 system_server 触发，不受我们进程生死影响，
 * 是应用内部最后一道能自我复活的防线。
 *
 * 间隔取 60 秒：车机熄火是**整机 suspend**，CPU 停止但 RTC 继续走，所以回来时这条
 * RTC_WAKEUP 闹钟已经过期、立刻触发 —— 休眠唤醒后一分钟内必须把手势服务、无障碍、
 * 仪表卡片全部摆正，用户才感觉是"自己起来的"。doze 下系统会自行限流到约 9 分钟一次，
 * 那是系统行为，设短不会多耗电；高频守护另有 native daemon（2 秒一轮）兜底。
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        GesturePrefs.init(app)
        LsLog.init(app)

        if (!GesturePrefs.enabled) {
            // 1.5.3beta2：手势关了，但自愈/休眠唤醒链不能断。
            // 不再 unschedule；只跳过手势服务，保留 Guard/Daemon/A11y/卡片自愈。
            LsLog.i(TAG, "手势未开启，保留自愈心跳")
            GuardService.start(app)
            DaemonGuard.start(app)
            Thread {
                runCatching { com.byd.ls.service.A11yHealer.healAsync(app, "heartbeat-idle") }
                if (GesturePrefs.clusterEnabled) {
                    runCatching { com.byd.ls.service.ClusterCardKeeper.heal(app, "heartbeat-idle") }
                }
            }.apply { isDaemon = true; name = "ls-hb-idle"; start() }
            schedule(app)
            return
        }

        val wasRunning = GlobalGestureService.isRunning()
        if (!wasRunning) {
            LsLog.w(TAG, "心跳发现手势服务不在，拉起")
            GlobalGestureService.start(app)
        }
        GuardService.start(app)
        DaemonGuard.start(app)      // 幂等，已在跑就跳过
        ShellManager.warmup(app)    // 顺带确认 shell 通道还活着（adbd 可能被重启过）

        // 通道若仍不可用就补一次 ADB 授权，让用户还有机会在车机屏幕上点「允许」。
        // 节流全在 AdbAutoAuth 内部（每次开机 2 次 + 间隔 10 分钟），心跳再密也不会反复弹窗；
        // 它自己会先做一次内存判断，通道好着时几乎零开销。
        // 必须扔到守护线程：onReceive 只有 ~10s 预算，而一次授权最长要等 60s。
        Thread {
            try {
                // 超时只作兜底：授权等用户 60s + 两轮 8s 探测 + 授权后无障碍重绑
                val ok = runBlocking {
                    withTimeoutOrNull(150_000L) {
                        AdbAutoAuth.maybeRequestAuthorization(app, "heartbeat")
                    } ?: false
                }
                if (ok) LsLog.i(TAG, "心跳补授权成功，特权通道已恢复")
            } catch (t: Throwable) {
                LsLog.w(TAG, "心跳自动授权异常: ${t.message}")
            }
        }.apply { isDaemon = true; name = "ls-hb-auth"; start() }

        // 仪表卡片：alarm 是 ROM 清理进程后唯一还能叫醒我们的路径（真车实测：
        // 用户从最近任务划掉 App 时，系统连 cluster.TICK 闹钟一起清掉，只剩这条 5 分钟心跳）。
        // 所以卡片自愈也必须挂在这里，否则「卡片没了但进程不在」时永远没人补。
        // 无障碍同理：休眠唤醒后绑定可能已经悬空，没有任何广播会告诉我们，
        // 只有这条闹钟能定期把 dumpsys 重新看一遍并补绑。
        Thread {
            runCatching { com.byd.ls.service.A11yHealer.healAsync(app, "heartbeat") }
            if (GesturePrefs.clusterEnabled) {
                runCatching { com.byd.ls.service.ClusterCardKeeper.heal(app, "heartbeat") }
            }
        }.apply { isDaemon = true; name = "ls-hb-card"; start() }

        LsLog.i(TAG, "心跳完成，服务此前存活=$wasRunning")
        schedule(app)               // 排下一次
    }

    companion object {
        private const val TAG = "Heartbeat"
        private const val ACTION = "com.byd.ls.action.HEARTBEAT"
        private const val REQ_CODE = 0x21
        private const val INTERVAL_MS = 60 * 1000L

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            try {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pendingIntent(ctx)
                )
            } catch (t: Throwable) {
                LsLog.w(TAG, "setExactAndAllowWhileIdle 失败，退回非精确闹钟: ${t.message}")
                runCatching {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + INTERVAL_MS,
                        pendingIntent(ctx)
                    )
                }
            }
        }

        /** 立即安排一次短延迟心跳（onTaskRemoved 兜底用） */
        fun scheduleSoon(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    pendingIntent(ctx)
                )
            }.onFailure { LsLog.w(TAG, "scheduleSoon 失败: ${it.message}") }
        }

        fun unschedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { am.cancel(pendingIntent(ctx)) }
        }

        private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, REQ_CODE,
            Intent(ctx, HeartbeatReceiver::class.java).setAction(ACTION).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
