package com.byd.ls.shell

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.byd.ls.util.LsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * shell 域 helper 桥（1.4.16）。
 *
 * 为什么需要它：`android.hardware.bydauto.statistic.BYDAutoStatisticDevice` 是 framework 类，
 * App 进程（untrusted_app）加载不了（hidden API + SELinux）。启迪桌面 3.2.0 的做法是用
 * `app_process` 在 shell(uid 2000) 域起一个常驻进程反射调用它——这正是"能读到车机综合油耗"
 * 的原因。我们复用仓库里已有的 `helper/src/.../HelperMain.java`（LocalSocket 常驻、校验对端 UID）。
 *
 * 生命周期：
 *  · [ensureReady] 把 assets/ls-helper.dex 推到 /data/local/tmp（sha 相同则跳过，零流量）
 *    并拉起 `app_process ... HelperMain <appUid>`；
 *  · [queryBlocking] 连 abstract socket `com.byd.ls.helper`，发一行 `statistic <method>`，
 *    读回数值；任何失败都返回 null，由调用方回退自算。
 */
object HelperBridge {

    private const val TAG = "LsHelper"
    private const val ASSET = "ls-helper.apk"
    private const val PROC_NAME = "ls-helper"
    private const val SOCKET = "com.byd.ls.helper"
    private const val TMP_DEX = "/data/local/tmp/ls-helper.apk"

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastRaw: String = ""
        private set
    @Volatile private var dexReady = false

    /** 供 VehicleBridge 拼 app_process 命令用 */
    @JvmStatic
    fun tmpDexPath(): String = TMP_DEX

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 拉起常驻 helper（1.4.32）。
     *
     * 关键写法：外层 `( … & )` 以 `)` 收尾，才不会与 adblib 追加的
     * `; echo __LS_EXIT__=$?` 拼成 `&;` 语法错误。
     *
     * 1.4.124：把 `setsid` 换成 `nohup`（docs/13 §19.5）。
     * DiLink 4 的 /system/bin/sh 遇到 `setsid … &` 后面接 `;` 直接报
     * "syntax error: unexpected ';'"，于是 ensureDaemon 永远回 false ——
     * 实车日志连续刷 `helper 常驻拉起 = false ()`，所有采样被迫退回 adblib。
     * nohup + 三重重定向同样能脱离 adbd（实测 pid 存活、日志正常），
     * 且不依赖 setsid 这条本机不吃的写法。
     */
    suspend fun ensureDaemon(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ShellManager.isPrivileged()) {
            lastError = "无特权通道"
            return@withContext false
        }
        if (!ensureDexOnly(ctx)) return@withContext false
        if (pidAlive(ctx)) return@withContext true
        val uid = android.os.Process.myUid()
        val cmd = "( nohup env CLASSPATH=$TMP_DEX app_process /system/bin" +
            " --nice-name=$PROC_NAME com.byd.ls.helper.HelperMain $uid" +
            " </dev/null >/data/local/tmp/ls-helper.log 2>&1 & ); sleep 1; pidof $PROC_NAME"
        val r = ShellManager.exec(ctx, cmd, 20_000L)
        val up = r.stdout.trim().isNotEmpty()
        LsLog.i(TAG, "helper 常驻拉起 = $up (${r.stdout.trim()})")
        if (!up) lastError = "常驻拉起失败 exit=${r.exitCode}"
        up
    }

    private suspend fun pidAlive(ctx: Context): Boolean =
        ShellManager.exec(ctx, "pidof $PROC_NAME", 4_000L).stdout.trim().isNotEmpty()

    /** 通过 abstract socket 向常驻 helper 发一行命令并取首行回复。 */
    @JvmStatic
    fun socketQuery(ctx: Context, cmd: String): String? = runBlocking {
        try {
            if (!ensureDaemon(ctx)) return@runBlocking null
            val s = LocalSocket()
            s.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
            s.outputStream.write((cmd + "\n").toByteArray())
            s.outputStream.flush()
            val text = s.inputStream.bufferedReader().readText()
            runCatching { s.close() }
            text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("__LS_EXIT__") }?.trim()
        } catch (t: Throwable) {
            lastError = "socket 异常：" + (t.message ?: t.javaClass.simpleName)
            null
        }
    }

    /**
     * 同 socketQuery，但返回完整多行回复（fid 全量批读是 129 行，取首行会丢数据）。
     * 常驻进程内直接 binder 读 → 不占 adblib 通道，也不会被 screencap 硬超时挤掉。
     */
    @JvmStatic
    fun socketQueryMulti(ctx: Context, cmd: String): String? = runBlocking {
        try {
            // 先试连：常驻进程活着就直接用，**完全不动 adblib 通道**。
            // 之前无条件走 ensureDaemon（内含 dex 推送）→ 通道被 screencap 占住时
            // 死在「写 dex 失败"，明明 daemon 已经活着也不干活。
            var s = connectNow()
            if (s == null) {
                if (!ensureDaemon(ctx)) return@runBlocking null
                s = connectNow() ?: return@runBlocking null
            }
            s.outputStream.write((cmd + "\n").toByteArray())
            s.outputStream.flush()
            val text = s.inputStream.bufferedReader().readText()
            runCatching { s.close() }
            val body = text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("__LS_EXIT__") }
                .joinToString("\n").trim()
            body.ifEmpty { null }
        } catch (t: Throwable) {
            lastError = "socket 异常：" + (t.message ?: t.javaClass.simpleName)
            null
        }
    }

    /** 直连常驻 helper 的 abstract socket（不经过 adblib）；没进程就返回 null。 */
    @JvmStatic
    fun connectNow(): LocalSocket? = try {
        val s = LocalSocket()
        s.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
        s
    } catch (t: Throwable) {
        null
    }

    private const val NAV_PROC = "ls-nav"
    private const val NAV_METRICS = "/data/local/tmp/.nav_metrics"
    private const val NAV_HEARTBEAT = "/data/local/tmp/.nav_heartbeat"
    private const val NAV_REQUEST = "/data/local/tmp/.nav_request"
    private const val NAV_ACTIVE = "/data/local/tmp/.nav_active"
    private const val NAV_PIXEL_MODE = "/data/local/tmp/.nav_pixel_mode"
    /** 同一时刻只允许一个 ensureNavWatch 进入“检查→拉起”临界区；
     *  navWatchFresh 在 helper 刚启动的前 1s 还不为真，并发调用会各起一条 ls-nav。 */
    private val navStartLock = java.util.concurrent.locks.ReentrantLock()

    /** 1.4.187 B1：采集进程空闲时只写心跳，指标是旧的；以心跳判断是否还活着。 */
    @JvmStatic
    fun navWatchFresh(): Boolean {
        val now = System.currentTimeMillis()
        val hb = java.io.File(NAV_HEARTBEAT).lastModified()
        val met = java.io.File(NAV_METRICS).lastModified()
        return (hb > 0L && now - hb < 7_000L) || (met > 0L && now - met < 2_500L)
    }

    /** 1.4.193：导航中让 ls-nav 进入 1.4.178 的 200ms 连续模式；退出导航立即撤掉。 */
    @JvmStatic
    fun setNavCaptureActive(ctx: Context, active: Boolean): Boolean = runBlocking {
        if (!ShellManager.isPrivileged()) return@runBlocking false
        val cmd = if (active) "touch $NAV_ACTIVE" else "rm -f $NAV_ACTIVE"
        runCatching {
            ShellManager.exec(ctx, cmd, 4_000L)
        }.getOrNull()?.success == true
    }

    /** 导航起止事件 → 让空闲的 ls-nav 立刻截 2 帧；不改变常驻节拍。 */
    @JvmStatic
    fun requestNavCapture(ctx: Context): Boolean = runBlocking {
        if (!ShellManager.isPrivileged()) return@runBlocking false
        runCatching {
            ShellManager.exec(ctx, "touch $NAV_REQUEST", 4_000L)
        }.getOrNull()?.success == true
    }

    /** 正常信号链路不截屏；只有信号失效才让 helper 进入像素兜底模式。 */
    @JvmStatic
    fun setNavPixelMode(ctx: Context, active: Boolean): Boolean = runBlocking {
        if (!ShellManager.isPrivileged()) return@runBlocking false
        val cmd = if (active) "touch $NAV_PIXEL_MODE"
        else "rm -f $NAV_PIXEL_MODE $NAV_ACTIVE"
        runCatching {
            ShellManager.exec(ctx, cmd, 4_000L)
        }.getOrNull()?.success == true
    }

    /** 拉起（或复用）连续采集进程：一次 adb 调用，之后采样全程不占 adbd。 */
    @JvmStatic
    fun ensureNavWatch(ctx: Context): Boolean = runBlocking {
        navStartLock.lock()
        try {
        if (!ShellManager.isPrivileged()) return@runBlocking false
        // 已经在跑（而且真在产出）就别重复拉起。实车踩过两种：每 10s 起一个新实例抢同一个文件；
        // 以及进程活着但 screencap 挂住、占比行不再更新 —— 后者要杀掉重启，不能只看 pidof。
        // 1.4.125：只看占比文件新鲜度，不再用 pidof 探活。
        if (navWatchFresh()) return@runBlocking true
        if (!ensureDexOnly(ctx)) return@runBlocking false
        // 1.4.125：拉起命令只发「一条平铺的简单命令」：sh <脚本>。
        // 实车连撞两次（18:22:37 / 18:24:44 都是 `拉起 = false (空)`）：
        // 把 while/if 控制结构或 `( … & )` 子 shell 拼进 adblib 的 service 串时，
        // 真车会静默不执行（exit=0、输出为空、进程根本没起来）—— ShellManager.kt:231 记着这条。
        // 所以把所有 shell 语法收进设备上的一份脚本，app 只负责「写脚本 + 跑脚本」。
        val script = "/data/local/tmp/lswatch.sh"
        val body = "#!/system/bin/sh\n" +
            // 旧 helper 的 cmdline 就是 `ls-nav`（--nice-name 已经把 argv 替换掉），
            // 用 nice-name=... 匹配不到它，会越拉越多；按完整进程名精确杀。
            "pkill -f '^ls-nav\$'\n" +
            "sleep 1\n" +
            "nohup env CLASSPATH=$TMP_DEX app_process /system/bin --nice-name=$NAV_PROC" +
            " com.byd.ls.helper.HelperMain --navwatch ${ctx.packageName}" +
            " >/data/local/tmp/ls-nav.log 2>&1 &\n" +
            "echo started\n"
        val b64 = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)
        // 1.4.126：通道会被别的调用（fid 轮询/看门狗/dex 校验）挤到退避里，一次写失败不代表脚本有问题。
        // 实车 19:56 的日志就是「printf … 被跳过 → navwatch 脚本写入失败 → sh … 被跳过 → 拉起 = false (空)」。
        var wrote = false
        for (attempt in 0 until 3) {
            val wr = runCatching {
                ShellManager.exec(ctx, "printf %s " + b64 + " | base64 -d > $script && chmod 755 $script", 10_000L)
            }.getOrNull()
            if (wr != null && wr.success) { wrote = true; break }
            LsLog.w(TAG, "navwatch 脚本写入失败（第 " + (attempt + 1) + " 次）：" + (wr?.stdout?.take(40) ?: "null"))
            delay(2_500L)
        }
        if (!wrote) return@runBlocking false
        var up = false
        for (attempt in 0 until 2) {
            // 先用最平的写法；adblib 的 service 串里带 `; echo …` 时真车偶尔整条不执行。
            val r = if (attempt == 0) {
                runCatching { ShellManager.exec(ctx, "sh $script", 15_000L) }.getOrNull()
            } else {
                runCatching { ShellManager.exec(ctx, "sh $script; echo spawned", 15_000L) }.getOrNull()
            }
            for (i in 0 until 10) {
                delay(500L)
                if (navWatchFresh()) { up = true; break }
            }
            LsLog.i(TAG, "navwatch 拉起（第 " + (attempt + 1) + " 次）= " + up +
                    " (" + (r?.stdout?.trim().orEmpty().ifEmpty { "空" }) + ")")
            if (up) break
        }
        up
        } finally {
            navStartLock.unlock()
        }
    }

    /** 界面不再采样时收工（helper 自己也会在 app 进程消失时退出）。 */
    @JvmStatic
    fun stopNavWatch(ctx: Context) = runBlocking {
        runCatching { ShellManager.exec(ctx, "pkill -f \"nice-name=$NAV_PROC\"", 6_000L) }
        Unit
    }

    /** 只推 dex（按 sha 跳过）；一次性模式下不需要常驻进程。 */
    suspend fun ensureDexOnly(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ShellManager.isPrivileged()) {
            lastError = "无特权通道"
            return@withContext false
        }
        try {
            val bytes = ctx.assets.open(ASSET).use { it.readBytes() }
            val want = sha256(bytes)
            if (!dexReady) {
                val cur = ShellManager.exec(ctx, "sha256sum $TMP_DEX", 6_000L).stdout.trim().substringBefore(' ')
                if (cur.isEmpty()) {
                    // 通道此刻读不到（nav_probe 的 screencap 硬超时会把通道关掉并静默 30/60 s）。
                    // 与其把调用方判死，不如放行：真跑不通时 exec/socket 自己会报错。
                    LsLog.w(TAG, "sha 读不到，跳过 dex 校验（通道降级）")
                    dexReady = true
                    return@withContext true
                }
                if (cur != want) {
                    LsLog.i(TAG, "推送 helper dex（$want → ${cur.ifEmpty { "缺失" }}）")
                    val w = ShellManager.writeBinaryChunked(ctx, bytes, TMP_DEX, mode = "644")
                    if (!w.success) {
                        // 实测 writeBinaryChunked 偶发返回 false 但文件已写对（11877 B 校验通过），
                        // 所以以「写后 sha 复核」为准，不轻信返回值。
                        val check = ShellManager.exec(ctx, "sha256sum $TMP_DEX", 6_000L)
                            .stdout.trim().substringBefore(' ')
                        if (check.isEmpty()) {
                            // 复核也读不到：同样是通道降级，放行让下游命令自己判定。
                            LsLog.w(TAG, "dex 复核读不到，放行")
                            dexReady = true
                            return@withContext true
                        }
                        if (check != want) {
                            lastError = "写 dex 失败：" + w.stdout.take(80) + " sha=" + check.take(12)
                            return@withContext false
                        }
                    }
                }
                dexReady = true
            }
            true
        } catch (t: Throwable) {
            lastError = "ensureReady 异常：" + (t.message ?: t.javaClass.simpleName)
            LsLog.w(TAG, lastError)
            false
        }
    }

    /**
     * 查询 statistic 方法（一次性调用，阻塞版，供 Java 采样线程用）。
     *
     * 1.4.17：不再常驻 + LocalSocket，改成一次 `app_process ... --once <method>`。
     * 原因见 HelperMain：adblib 的 service 串会追加哨兵，`&` 结尾会变成 `&;` 语法错误；
     * setsid / sh -c '&' 又会因 fd 继承让 adbd 等不到 CLSE 而超时。一次性调用最稳。
     */
    @JvmStatic
    fun queryBlocking(ctx: Context, method: String): Double? = runBlocking {
        try {
            if (!dexReady) {
                if (!ensureDexOnly(ctx)) return@runBlocking null
            }
            val cmd = "CLASSPATH=$TMP_DEX app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --once $method"
            val r = ShellManager.exec(ctx, cmd, 15_000L)
            val line = r.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            lastRaw = line
            if (line.startsWith("ERR") || line.isEmpty()) {
                lastError = line.ifEmpty { "空输出（exit=${r.exitCode}）" }
                null
            } else line.toDoubleOrNull()
        } catch (t: Throwable) {
            lastError = "query 异常：" + (t.message ?: t.javaClass.simpleName)
            null
        }
    }

    fun statusText(): String = when {
        !dexReady -> "未就绪（dex 未推送）"
        lastRaw.isNotEmpty() -> lastRaw
        lastError.isNotEmpty() -> "失败：" + lastError.take(60)
        else -> "就绪"
    }
}
