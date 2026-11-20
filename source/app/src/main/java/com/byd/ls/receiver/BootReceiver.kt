package com.byd.ls.receiver

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.byd.ls.config.GesturePrefs
import com.byd.ls.guard.DaemonGuard
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.BootAssistJob
import com.byd.ls.service.BootJobService
import com.byd.ls.service.ClusterCardKeeper
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.shell.AdbAutoAuth
import com.byd.ls.shell.AdbManager
import com.byd.ls.shell.PrivilegeBootstrap
import com.byd.ls.shell.ShellManager
import com.byd.ls.shell.WatchdogInstaller
import com.byd.ls.util.LsLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 开机静默自启。
 *
 * 需求：车机开机时只在后台启动服务与守护，**绝不弹 UI**。
 *
 * 1.2.22 修复"开机不自启，必须手动打开 App 才恢复"：
 *  · 原条件是 `enabled || grantDone`，而 grantDone 只在「一键启动」成功后才写 →
 *    用户配好了却从没点过一键启动（或重装后标记被清），开机就整段跳过。现在只看
 *    [GesturePrefs.enabled]，并且**首启默认值改为开**（见 GesturePrefs）；
 *  · 拉起逻辑抽成 [beginQuietStart]：BootReceiver、LsApp.onCreate（进程被任何方式
 *    叫醒时）、BootJobService、看门狗广播四处共用同一份幂等实现，避免"哪条路都差一点"；
 *  · 加两条系统级兜底：
 *      1) BootJobService 保持 setPersisted 周期任务（15 分钟）；
 *      2) 额外排一个 **3 分钟延迟的一次性持久化 Job**（[scheduleBootAssist]）——
 *         开机瞬间 adbd / 无障碍 / 电池管理都还没稳定，第一次拉起往往被压制，
 *         隔 3 分钟再补一次命中率高得多（现场 2 次实测都在这条路径上恢复）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return
        // 1.4.78：车机刚唤醒会补推一轮当前值，此刻重注册最容易拿到快照
        if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
            runCatching { com.byd.ls.shell.BydDirect.resync() }
            // 1.4.93: 认出休眠唤醒 → 立刻逼 AMS 重评(不等 60s watchdog 节拍)
            if (com.byd.ls.service.A11yHealer.noteWakeSignals(context)) {
                com.byd.ls.service.A11yHealer.wakeRecoveryAsync(context, "resume")
            }
        }

        val app = context.applicationContext
        GesturePrefs.init(app)
        LsLog.init(app)
        LsLog.enabled = GesturePrefs.logEnabled
        LsLog.i(TAG, "收到广播 $action")

        // TICK 是 15s 心跳，正常时不再每轮写 boot.log；只在异常/自愈时落盘。
        if (action != ACTION_CLUSTER_TICK) {
            writeBootLog(app, "action=$action enabled=${GesturePrefs.enabled} grantDone=${GesturePrefs.grantDone}")
        }

        // 这台车机熄火是休眠不是断电（实测 uptime 连续累加，BOOT_COMPLETED 永远不来），
        // 点火时没有任何系统广播叫醒我们 —— 只有这条链式闹钟能叫醒。每次进来先把下一次排上，
        // 进程随后被 ROM 杀掉也还有下一次机会。
        runCatching {
            com.byd.ls.cluster.service.AlarmKeeper.schedule(
                app,
                com.byd.ls.cluster.data.Card.beatAge() < 60_000L
            )
        }

        if (action == ACTION_CLUSTER_TICK) {
            // 定时兜底：先确认手势服务还在。这台车机熄火是休眠、点火没有任何广播，
            // 唤醒后系统把主进程回收掉时，只有这条闹钟能把手势服务拉回来。
            if (GesturePrefs.enabled && !GlobalGestureService.isRunning()) {
                runCatching { GlobalGestureService.start(app) }
                runCatching { DaemonGuard.start(app) }
                LsLog.i(TAG, "TICK：手势服务不在，已拉起")
                writeBootLog(app, "tick heal gesture")
            }
            // 看门狗自愈：开机那次若因通道没通而失败，这里是每 60s 一次的补装机会
            // （ensure 内部有 30s 防抖，不会每次都真跑）。onReceive 只有 ~10s 预算，
            // 而分块写脚本要十几次 exec，必须丢到后台线程。
            Thread {
                runCatching {
                    runBlocking {
                        withTimeoutOrNull(25_000L) { WatchdogInstaller.ensure(app, "tick") }
                    }
                }
            }.apply { isDaemon = true; name = "ls-tick-wd"; start() }
            // 再补卡片与通知监听：不重跑整套启动（省电，也不打扰 adbd）
            ClusterCardKeeper.heal(app, "tick")
            return
        }

        beginQuietStart(app, "boot:$action")
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** 仪表模块的链式闹钟动作（AlarmKeeper 发，本接收器收） */
        const val ACTION_CLUSTER_TICK = "com.byd.ls.cluster.TICK"

        /** 开机后等 adbd / systemui 就位再发公钥，这个窗口内弹窗成功率最高 */
        const val AUTO_AUTH_DELAY_MS = 15_000L

        /** 授权最长等用户 60s，外加两轮 8s 静默探测和授权后的无障碍重绑，留足余量 */
        const val AUTO_AUTH_TIMEOUT_MS = 150_000L

        /** 1.4.137：投屏档位下发前的固定等待（等 AutoContainer 服务 publish） */
        const val PROJECTION_DELAY_MS = 3_000L

        /** 1.4.137：同一进程内两条自启信号之间最小的重发间隔 */
        const val PROJECTION_GAP_MS = 10 * 60 * 1000L

        @Volatile
        private var lastProjectionAt = 0L

        /** 开机 3 分钟后的一次性补拉（JobScheduler persisted） */
        const val BOOT_ASSIST_JOB_ID = 4102
        const val BOOT_ASSIST_DELAY_MS = 3 * 60 * 1000L

        val HANDLED_ACTIONS = setOf(
            ACTION_CLUSTER_TICK,
            Intent.ACTION_USER_PRESENT,
            // 1.4.164：manifest 已注册 SCREEN_ON，但这里漏了，唤醒广播被直接 return 丢掉了。
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED
        )

        private fun writeBootLog(ctx: Context, line: String) {
            runCatching {
                // release 包非 debuggable，run-as 读不到 filesDir，所以两个位置都写：
                // 外部目录 /sdcard/Android/data/com.byd.ls/files/boot.log 可直接 adb pull
                ctx.getExternalFilesDir(null)?.let { appendBoot(File(it, "boot.log"), line) }
                appendBoot(File(ctx.filesDir, "boot.log"), line)
            }
        }

        /** 只留最近 32KB：排障用文件不能无界增长 */
        private fun appendBoot(f: File, line: String) {
            if (f.exists() && f.length() > 32 * 1024) f.writeText("")
            f.appendText("${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())} $line\n")
        }

        /**
         * 幂等静默启动：任何"进程被叫醒"的时机都可以调。
         *
         * 顺序刻意如此：
         *  1. 同步在调用线程里 startForegroundService（onReceive 只有 ~10s 预算，别拖到后台线程）；
         *  2. 再排 JobScheduler（15 分钟周期 + 3 分钟一次性补拉）；
         *  3. 最后才在网络线程里尝试 ADB 与无障碍修复 —— 这一步慢、可能等用户点授权。
         */
        fun beginQuietStart(app: Context, reason: String) {
            if (!GesturePrefs.enabled) {
                LsLog.i(TAG, "手势总开关未开启，跳过启动（${reason}）")
                return
            }

            runCatching { GlobalGestureService.start(app) }
                .onFailure { LsLog.w(TAG, "启动手势服务失败: ${it.message}") }
            runCatching { GuardService.start(app) }
                .onFailure { LsLog.w(TAG, "启动守护服务失败: ${it.message}") }
            runCatching { HeartbeatReceiver.schedule(app) }
            runCatching { BootJobService.schedule(app) }
            runCatching { scheduleBootAssist(app) }

            // 【1.4.8 开机提速】卡片不等 ADB/a11y：它有自己的 display 与窗口，
            // 只在需要读车机数据（油耗/媒体）时才用通道，先拉起来再补数据。
            // 实测旧链路 BOOT→卡片 ≈ 50s（docs/04），主要花在「warmup + autoConnectKnown(8s)
            // + PrivilegeBootstrap.repair(≤20s) + 看门狗 8 轮」之后才轮到 heal("boot")。
            runCatching { ClusterCardKeeper.heal(app, "boot-fast") }

            Thread {
                try {
                    runCatching { DaemonGuard.start(app) }
                    runCatching { ShellManager.warmup(app) }

                    // 先试已有 ADB 端点（key 授权过一次后，重启可直接连），成功就 repair。
                    // 【1.4.8】超时 8s → 3s：连不上就交给后续心跳，别阻塞开机链路。
                    val adb = runBlocking {
                        withTimeoutOrNull(3_000L) {
                            runCatching { AdbManager.autoConnectKnown(app) }.getOrDefault(false)
                        } ?: false
                    }
                    if (adb) {
                        LsLog.i(TAG, "自动连上 ADB 端点，执行权限/无障碍修复")
                        runBlocking {
                            withTimeoutOrNull(20_000L) {
                                runCatching { PrivilegeBootstrap.repair(app) }
                            }
                        }
                        // 用户打开过“禁用 BYD 诊断日志”时，开机在 ADB 成功分支幂等重放 tag 压制。
                        // 放在这里而不是 TICK：TICK 是高频路径，不能每轮跑脚本。
                        if (GesturePrefs.bydLogDisabled) {
                            val ok = runBlocking {
                                withTimeoutOrNull(20_000L) {
                                    runCatching { com.byd.ls.shell.BydLogSwitch.reapplyIfNeeded(app) }
                                        .getOrDefault(false)
                                } ?: false
                            }
                            LsLog.i(TAG, "BYD 诊断日志开机对账=${if (ok) "ok" else "fail/absent"}")
                            writeBootLog(app, "byd-log reapply=${if (ok) "ok" else "fail/absent"}")
                        }
                    } else {
                        LsLog.i(TAG, "未自动连上 ADB 端点，使用已有权限做无障碍自愈")
                        A11yHealer.healAsync(app, reason, delayMs = 1_500)
                    }
                    // 仪表卡片：开机/点火后补一次（内部按 clusterEnabled 与屏幕状态自行决定动不动作）
                    runCatching { ClusterCardKeeper.heal(app, "boot") }
                    // 1.4.137：仪表投屏档位打到 16（全屏地图 + 顶部横条导航卡）。
                    // 服务由 XdjaContainerService 在自身启动时 publish，开机瞬间不一定已注册，
                    // 所以固定延迟 3 秒再发（1.4.139 按现场要求改回 3 s）；重复发 16 无副作用，同一次开机只发一次。
                    runCatching { scheduleProjection(app, reason) }
                    // 看门狗：进程被清之后唯一还能把 App 拉回来的外部力量
                    // （脚本已在跑时只做一次存活探测，不会重复安装）
                    //
                    // 单发一次不够：开机 10s 内 adblib 还没握手，ensure 会判「无特权通道」
                    // 直接返回；真车实测通道要到开机约 100s 才通（00:30:09 boot → 00:31:45
                    // 「选用内置 ADB（adblib）通道」），而 00:31:46 进程就被 stop 杀掉了。
                    // 所以最多重试 8 次、间隔 15s（总 ≤120s），一命中「运行中」就退出。
                    for (wdTry in 1..8) {
                        val st = runBlocking {
                            withTimeoutOrNull(20_000L) {
                                runCatching {
                                    WatchdogInstaller.ensure(app, "boot", force = wdTry > 1)
                                }.getOrDefault("")
                            } ?: ""
                        }
                        if (st.contains("运行中")) break
                        if (wdTry < 8) Thread.sleep(15_000L)
                    }
                    writeBootLog(app, "quiet-start done reason=$reason adb=$adb")
                } catch (t: Throwable) {
                    LsLog.e(TAG, "静默启动失败", t)
                }
            }.apply { isDaemon = true; name = "ls-boot"; start() }

            // 这条只在开机广播里排（原因见类注释）；进程内重复调用会被 BootJobService 的幂等跳过
            if (reason.startsWith("boot:")) scheduleAutoAuth(app)
        }

        /**
         * 开机约 15s 后再试一次 ADB 授权：开机瞬间 adbd / 系统服务还没就绪，
         * 探测太早会把「还没回包」误判成端口没开，白烧掉本次开机的授权额度。
         */
        private fun scheduleAutoAuth(app: Context) {
            Thread {
                try {
                    Thread.sleep(AUTO_AUTH_DELAY_MS)
                    val ok = runBlocking {
                        withTimeoutOrNull(AUTO_AUTH_TIMEOUT_MS) {
                            AdbAutoAuth.maybeRequestAuthorization(app, "boot")
                        } ?: false
                    }
                    LsLog.i(TAG, "开机自动授权流程结束：authorized=$ok")
                } catch (t: Throwable) {
                    LsLog.w(TAG, "开机自动授权异常: ${t.message}")
                }
            }.apply { isDaemon = true; name = "ls-boot-auth"; start() }
        }

        /**
         * 1.4.137：开机把仪表投屏打到 16 档（全屏投屏）。
         *
         * 时序：服务 publish 在开机几百毫秒后，太早发会拿到 Service not found，
         * 所以固定等 [PROJECTION_DELAY_MS]；一次性任务用普通线程即可（进程被杀就没了，
         * 反正下一次自启信号会再来一遍）。同一进程内 [PROJECTION_GAP_MS] 内不重复发，
         * 避免 USER_PRESENT / CLUSTER_TICK / onCreate 三条路叠加成一串命令。
         */
        fun scheduleProjection(app: Context, reason: String) {
            if (!GesturePrefs.projectionFullOnBoot) {
                LsLog.i(TAG, "仪表全屏投屏开关关闭，跳过（$reason）")
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastProjectionAt < PROJECTION_GAP_MS) return
            lastProjectionAt = now
            Thread {
                try {
                    Thread.sleep(PROJECTION_DELAY_MS)
                    val code = runBlocking {
                        withTimeoutOrNull(12_000L) {
                            com.byd.ls.cluster.ClusterProjection.apply(app)
                        } ?: Int.MIN_VALUE
                    }
                    LsLog.i(TAG, "仪表投屏档位 16 结果=$code（$reason）")
                    writeBootLog(app, "projection mode=16 code=$code reason=$reason")
                } catch (t: Throwable) {
                    LsLog.w(TAG, "仪表投屏下发异常: ${t.message}")
                }
            }.apply { isDaemon = true; name = "ls-boot-proj"; start() }
        }

        /**
         * 一次性延迟补拉。
         *
         * 为什么不用 Handler.postDelayed：进程随时可能被 ROM 整组回收，延时任务随之消失；
         * JobScheduler 的 persisted 任务由 system_server 持有，重启后依然存在。
         */
        fun scheduleBootAssist(ctx: Context) {
            if (!GesturePrefs.enabled) return
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            runCatching {
                if (js.getPendingJob(BOOT_ASSIST_JOB_ID) != null) return
                val job = JobInfo.Builder(
                    BOOT_ASSIST_JOB_ID,
                    ComponentName(ctx, BootAssistJob::class.java)
                )
                    .setPersisted(true)
                    .setMinimumLatency(BOOT_ASSIST_DELAY_MS)
                    .setOverrideDeadline(BOOT_ASSIST_DELAY_MS + 60_000L)
                    .build()
                LsLog.i(TAG, "开机补拉 Job 排程结果=${js.schedule(job)}")
            }.onFailure { LsLog.w(TAG, "开机补拉 Job 排程失败: ${it.message}") }
        }
    }
}
