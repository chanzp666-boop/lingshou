package com.byd.ls.guard

import android.content.Context
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.util.LsLog
import java.io.File

/**
 * NDK 双进程守护的 Java 层封装。
 *
 * native 侧（cpp/lsguard.cpp）从 App 进程 fork 出一个双 fork 脱离会话的原生守护进程，
 * 每 [CHECK_INTERVAL_SEC] 秒扫一次 /proc，只要发现 com.byd.ls 的任何进程都不在了，
 * 就用 `am start-foreground-service` 把主服务和守护服务拉回来；连续两轮拉不起来时
 * 再补一发 BOOT_COMPLETED 广播兜底。
 *
 * 停止方式：写一个 stop 标记文件，守护进程在下一轮自检时优雅退出。
 * 之所以不用 kill —— 守护进程对 SIGTERM 免疫，正是为了防止被系统顺手清理掉。
 */
object DaemonGuard {

    private const val TAG = "DaemonGuard"
    private const val LIB = "lsguard"
    private const val CHECK_INTERVAL_SEC = 2

    private const val PID_FILE = "ls_daemon.pid"
    private const val STOP_FILE = "ls_daemon.stop"
    private const val LOG_FILE = "ls_daemon.log"

    private const val PKG = "com.byd.ls"

    @Volatile var nativeLoaded: Boolean = false
        private set

    @Volatile var loadError: String = ""
        private set

    init {
        nativeLoaded = try {
            System.loadLibrary(LIB)
            LsLog.i(TAG, "lib$LIB.so 加载成功：${nativeVersion()}")
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            LsLog.e(TAG, "lib$LIB.so 加载失败（NDK 未编译？abi 不匹配？）", t)
            false
        }
    }

    private external fun nativeStartDaemon(
        pkg: String,
        compsCsv: String,
        stopFile: String,
        pidFile: String,
        logFile: String,
        intervalSec: Int
    ): Int

    private external fun nativeStopDaemon(stopFile: String)
    private external fun nativeDaemonPid(pidFile: String): Int
    private external fun nativeDaemonAlive(): Boolean
    private external fun nativeVersion(): String

    /** 守护进程要拉起的组件列表 */
    private fun componentsCsv(): String = listOf(
        "$PKG/${GlobalGestureService::class.java.name}",
        "$PKG/${GuardService::class.java.name}"
    ).joinToString(",")

    /**
     * 启动守护（幂等，主进程与 :guard 进程都可以调用）。
     * @return 守护进程 pid，失败返回 -1
     */
    fun start(ctx: Context): Int {
        if (!nativeLoaded) {
            LsLog.w(TAG, "native 库未加载，跳过守护启动：$loadError")
            return -1
        }
        return try {
            val dir = ctx.filesDir.absolutePath
            val pid = nativeStartDaemon(
                PKG,
                componentsCsv(),
                File(dir, STOP_FILE).absolutePath,
                File(dir, PID_FILE).absolutePath,
                File(dir, LOG_FILE).absolutePath,
                CHECK_INTERVAL_SEC
            )
            LsLog.i(TAG, "守护进程 pid=$pid")
            pid
        } catch (t: Throwable) {
            LsLog.e(TAG, "启动守护进程失败", t)
            -1
        }
    }

    fun stop(ctx: Context) {
        if (!nativeLoaded) return
        try {
            nativeStopDaemon(File(ctx.filesDir, STOP_FILE).absolutePath)
            LsLog.i(TAG, "已请求守护进程停止")
        } catch (t: Throwable) {
            LsLog.w(TAG, "停止守护进程失败: ${t.message}")
        }
    }

    fun daemonPid(ctx: Context): Int {
        if (!nativeLoaded) return -1
        return try {
            nativeDaemonPid(File(ctx.filesDir, PID_FILE).absolutePath)
        } catch (t: Throwable) { -1 }
    }

    /** socket 探活：不依赖 pid 文件（旧 pid 文件/重复启动会误报“未运行”） */
    fun isDaemonAlive(): Boolean = nativeLoaded && runCatching { nativeDaemonAlive() }.getOrDefault(false)

    /** 界面状态栏用 */
    fun statusText(ctx: Context): String = when {
        !nativeLoaded -> "native 库未加载（$loadError）"
        isDaemonAlive() -> {
            val pid = daemonPid(ctx)
            if (pid > 0) "运行中 pid=$pid，每 ${CHECK_INTERVAL_SEC}s 巡检"
            else "运行中（socket 监听，pid 文件待刷新）"
        }
        else -> "未运行"
    }

}
