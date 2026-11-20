package com.byd.ls.cluster

import android.annotation.SuppressLint
import android.content.Context
import com.byd.ls.shell.ShellManager
import kotlinx.coroutines.runBlocking

/**
 * 仪表模块的特权执行胶水层。
 *
 * 仪表助理原实现自带一套裸 ADB 客户端（YBAdb），合并进灵手后统一走 adblib 通道
 * （ShellManager.exec），此对象只做「Java 静态调用 → Kotlin suspend」的桥接，
 * 不承载任何业务逻辑。
 *
 * 返回语义对齐原 YBAdb.exec(String cmd, int timeoutMs)：
 *  - 成功：命令 stdout
 *  - 失败/未初始化：空串（调用方按「没拿到」处理，不抛异常）
 *
 * 约束：exec 内部是 runBlocking，**禁止在主线程调用**。仪表侧唯一调用点
 * NavProbe.sample() 原本就跑在后台采样线程里，接线时必须继续保持这个前提，
 * 否则会重演「主线程阻塞 → 无障碍绑定超时」的老问题。
 */
object ClusterExec {

    @SuppressLint("StaticFieldLeak")   // 存的是 applicationContext，不持有 Activity
    private var app: Context? = null

    /** 由 LsApp.onCreate 调用一次。 */
    @SuppressLint("StaticFieldLeak")
    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /**
     * 对齐原 YBAdb.connect(ctx)：灵手 adblib 是「用完即断 + 按需连接」，
     * 不存在常驻预热线程，这里只触发一次通道预热（后台），返回即走。
     */
    @JvmStatic
    fun connect(ctx: Context) {
        runCatching { ShellManager.warmup(ctx) }
    }

    /** 对齐原 YBAdb.ready()：不阻塞、不建连，只读通道健康位。 */
    @JvmStatic
    fun ready(): Boolean = runCatching { ShellManager.adbLibChannel().isReady() }.getOrDefault(false)

    /** 对齐原 YBAdb.status()：只有 ok / off 两态，灵手不做「connecting」中间态。 */
    @JvmStatic
    fun status(): String = if (ready()) "ok" else "off"

    /**
     * 特权命令保护窗口。
     *
     * 拉卡片（am start-activity --display 1）这类关键命令和 NavProbe 的每秒截屏
     * 抢的是同一个 adbd 槽位：实测每秒 1 次握手时 adbd 直接回
     * "Stream open actively rejected by remote peer"，卡片就拉不起来。
     * 关键命令执行前后开一个窗口，窗口内 NavProbe 主动让路。
     */
    @Volatile private var protectUntil = 0L

    @JvmStatic
    fun protectFor(ms: Long) {
        protectUntil = android.os.SystemClock.elapsedRealtime() + ms
    }

    /** 1.4.124：给 NavProbe 这类没有 Context 的调用方取 app 上下文（未 init 时返回 null）。 */
    @JvmStatic
    fun ctx(): Context? = app

    @JvmStatic
    fun isProtected(): Boolean = android.os.SystemClock.elapsedRealtime() < protectUntil

    /**
     * 慢命令专用（1.4.120）：dash 屏 screencap 这类「本来就慢」的命令走这里，
     * 超时只影响本次返回，不把整条 adblib 通道判坏。
     */
    @JvmStatic
    fun execBestEffort(cmd: String, timeoutMs: Long): String {
        val ctx = app ?: return ""
        return runCatching {
            runBlocking { ShellManager.exec(ctx, cmd, timeoutMs, true) }
        }.getOrNull()?.stdout ?: ""
    }

    @JvmStatic
    fun exec(cmd: String, timeoutMs: Long): String {
        val ctx = app ?: return ""
        return runCatching {
            runBlocking { ShellManager.exec(ctx, cmd, timeoutMs) }
        }.getOrNull()?.stdout ?: ""
    }
}
