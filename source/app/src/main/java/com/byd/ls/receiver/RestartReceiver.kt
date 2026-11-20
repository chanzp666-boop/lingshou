package com.byd.ls.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.byd.ls.config.GesturePrefs
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.LsAccessibilityService
import com.byd.ls.service.ClusterCardKeeper
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.util.LsLog

/**
 * 外部拉起入口（给 UID 2000 的 shell 看门狗 / native 守护用）。
 *
 * 为什么不能直接 `am start-foreground-service -n ...`：
 * GlobalGestureService 是 `android:exported="false"`，shell(UID 2000) 不是本应用 UID，
 * Android 10 会直接报 "Requires permission not exported" —— 1.1.0 的看门狗和 native
 * 守护实际上一直卡在这里，只是错误被日志淹没了。
 *
 * 改成导出的广播接收器：shell 发一条显式广播，接收器在应用进程里本地 startForegroundService，
 * 不经过 exported 检查，也不需要任何敏感权限。只有总开关/授权标记为真时才拉起，避免被滥用。
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // 1.4.61：通知里的两个自愈按钮（只动自己：手势服务 / 仪表卡片）
        when (intent?.action) {
            ACTION_HEAL_GESTURE -> {
                runCatching { A11yHealer.healAsync(context, "notif-heal") }
                return
            }
            ACTION_HEAL_CARD -> {
                Thread { runCatching { ClusterCardKeeper.heal(context, "notif-card") } }.apply { isDaemon = true }.start()
                return
            }
        }
        if (intent?.action == ACTION_A11Y_SOFT_RESET) {
            val ok = LsAccessibilityService.instance?.softReset() ?: false
            LsLog.e("RestartReceiver", "A11Y_SOFT_RESET disableSelf=" + ok)
            return
        }
        if (intent?.action != ACTION_RESTART) return
        val app = context.applicationContext
        GesturePrefs.init(app)
        LsLog.init(app)

        // 说明：Android 10 的 BroadcastReceiver 没有可用的发送方 UID API
        // （getSentFromUid 仅 API 34），这里只做“开关开启”校验；它只能拉起 FGS，
        // 不能执行特权命令，风险是重复拉起/耗电，不是提权。

        if (!GesturePrefs.enabledFromDisk(app)) {
            LsLog.i(TAG, "收到重启广播，但手势总开关未开启，忽略")
            return
        }
        LsLog.i(TAG, "收到重启广播，拉起手势服务与守护进程")
        runCatching { GlobalGestureService.start(app) }
            .onFailure { LsLog.w(TAG, "startForegroundService 失败: ${it.message}") }
        runCatching { GuardService.start(app) }
            .onFailure { LsLog.w(TAG, "启动 GuardService 失败: ${it.message}") }

        // 看门狗拉起我们的唯一目的是"让功能回来"，所以这里必须把三段自愈一起补上：
        // 无障碍绑定、仪表卡片、以及看门狗自身存活。
        // 以前这里只 startForegroundService，卡片要等下一次 60s 巡检才回来，
        // 现场观感就是"拉起来了但卡片还是不显示"。
        Thread({
            runCatching { com.byd.ls.service.A11yHealer.healAsync(app, "restart-rx", delayMs = 800) }
            runCatching { com.byd.ls.service.ClusterCardKeeper.heal(app, "restart-rx") }
            runCatching { kotlinx.coroutines.runBlocking { com.byd.ls.shell.WatchdogInstaller.ensure(app, "restart-rx") } }
        }, "restart-heal").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "RestartRx"
        const val ACTION_RESTART = "com.byd.ls.action.RESTART"
        const val ACTION_HEAL_GESTURE = "com.byd.ls.action.HEAL_GESTURE"
        const val ACTION_HEAL_CARD = "com.byd.ls.action.HEAL_CARD"
        const val ACTION_A11Y_SOFT_RESET = "com.byd.ls.action.A11Y_SOFT_RESET"
    }
}
