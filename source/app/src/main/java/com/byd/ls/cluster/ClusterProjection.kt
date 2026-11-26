package com.byd.ls.cluster

import android.content.Context
import com.byd.ls.config.GesturePrefs
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog
import com.byd.ls.cluster.FeatureGate
import kotlinx.coroutines.runBlocking

/**
 * 仪表投屏档位（1.4.137）。
 *
 * 背景（docs/13 §30）：
 *  · 仪表是独立的一块 1920x720 屏（display 1）。Android 侧永远输出整块画布，
 *    缩放到「整屏地图 + 顶部横条卡」还是「双表盘页 + 中间小窗」由**仪表 MCU** 决定，
 *    所以看截图分不出档位（16 与 17 的 screencap 图 md5 完全相同）。
 *  · 切换入口是跨进程 binder：服务名 AutoContainer（com.xdja.containerservice，
 *    system uid 1000 注册），AIDL 为 android.os.IAutoContainer。
 *      sendJson(int,String)             = 1
 *      sendInfo(int,int,String)         = 2   ← 我们要的
 *      sendInfo2(int,byte[])            = 3
 *      registerCallback                 = 4
 *      getProjectionDisplayInfo         = 5（恒抛 Not allowed，读不到当前档位）
 *    证据：framework.jar 的 classes2.dex 里 IAutoContainer$Stub$Proxy 每个方法自带
 *    transact 常量：getProjectionDisplayInfo=5 / registerCallback=4 / sendInfo=2 /
 *    sendInfo2=3 / sendJson=1。
 *  · 服务端 AutoContainerService.checkSendPermissionAndAllowType 校验同签名 + uid==1000
 *    或白名单 → 灵手 app 进程（uid 10084）会被拒；**shell uid 2000 能过**
 *    （checkSignatures(1000,2000)==0），所以走 adblib 的 service call。
 *
 * 档位码：
 *  · 16 = 全屏投屏：整屏地图 + 顶部横条导航卡（用户要的）
 *  · 17 = 半屏投屏：双表盘页（左 kW 右 km/h），导航缩进中间小窗（默认态）
 *  · 18 = 投屏关闭
 *
 * 不持久：车机重启、方向盘/中控切页、灵手被 force-stop 后 CardActivity 重建都会打回 17。
 * 重复发 16 无副作用（实测不闪屏不报错），所以补发是安全的。
 */
object ClusterProjection {

    private const val TAG = "LsProjection"

    const val SERVICE = "AutoContainer"
    const val DESCRIPTOR = "android.os.IAutoContainer"

    /** IAutoContainer.sendInfo(int type, int infoInt, String str) 的 transact 号 */
    const val TX_SEND_INFO = 2

    /** type = 1000：ivi→cluster 通道固定值 */
    const val TYPE_IVI_TO_CLUSTER = 1000

    const val MODE_FULL = 16
    const val MODE_HALF = 17
    const val MODE_CLOSE = 18

    /** 服务端返回码：第一个 int 0 = 接受 */
    const val OK = 0
    /** service 报 Service not found —— 服务还没 publish（开机会有这几百毫秒） */
    const val ERR_NO_SERVICE = -1
    /** 通道没通 / 输出解析不了 */
    const val ERR_CHANNEL = -2

    /**
     * 走 adb shell 的完整命令。uid 2000 由 adbd 提供，服务端签名校验直接通过。
     * s16 "" = 一个空字符串实参（service call 的写法）。
     */
    fun command(mode: Int = MODE_FULL): String =
        "service call $SERVICE $TX_SEND_INFO i32 $TYPE_IVI_TO_CLUSTER i32 $mode s16 \"\""

    /**
     * 解析 service call 的输出。
     * 成功：Result: Parcel(00000000 00000000   '........') 或跨行写法
     * 失败：Error: ... / Service ... not found
     */
    fun parseResult(out: String?): Int {
        val t = out?.trim().orEmpty()
        if (t.isEmpty()) return ERR_CHANNEL
        if (t.contains("not found", ignoreCase = true)) return ERR_NO_SERVICE
        val m = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(t) ?: return ERR_CHANNEL
        return m.groupValues[1].toLong(16).toInt()
    }

    @Volatile
    var lastCode: Int = Int.MIN_VALUE
        private set

    @Volatile
    var lastAt: Long = 0L
        private set

    @Volatile
    var lastDetail: String = "未尝试"
        private set

    /** 发一次档位；返回 [OK] 表示服务端已接受。quiet=true 时不打成功日志（1.5 兜底补发用） */
    suspend fun apply(ctx: Context, mode: Int = MODE_FULL, timeoutMs: Long = 8_000L, quiet: Boolean = false): Int {
        remember(ctx)
        val cmd = command(mode)
        val out = runCatching {
            ShellManager.exec(ctx, cmd, timeoutMs, bestEffort = true)
        }.getOrNull()
        val code = parseResult(out?.stdout)
        lastCode = code
        lastAt = System.currentTimeMillis()
        lastDetail = (out?.stdout ?: "").trim().replace('\n', ' ').take(120)
        if (code == OK) { if (!quiet) LsLog.i(TAG, "投屏档位 $mode 已下发（服务端接受）") }
        else LsLog.w(TAG, "投屏档位 $mode 未确认 code=$code out=$lastDetail")
        return code
    }

    // ===== 1.5.0-beta2：全屏导航下，MCU 一旦被任何重绘打回 17 就盲补 16 =====
    /** 盲补周期：读不到档位（AutoContainer 5 恒抛），只能定周期兜底重发；单次 service call 约 45ms */
    private const val KEEP_FULL_MS = 200L
    @Volatile private var keepOn = false

    /** 开关打开时启动兜底线程：点仪表任何功能/卡片重绘导致掉档，最迟一个周期拉回全屏。 */
    @JvmStatic
    fun startKeepFull(ctx: Context) {
        if (!FeatureGate.full()) return
        remember(ctx)
        if (keepOn) return
        keepOn = true
        val app = ctx.applicationContext
        val t = Thread {
            while (keepOn && FeatureGate.full()) {
                try { runBlocking { apply(app, MODE_FULL, quiet = true) } } catch (e: Throwable) {}
                try { Thread.sleep(KEEP_FULL_MS) } catch (e: InterruptedException) { break }
            }
        }
        t.isDaemon = true
        t.name = "ls-keep-full"
        t.start()
        LsLog.i(TAG, "全屏保持已启动（周期 ${KEEP_FULL_MS}ms 盲补 16）")
    }

    @JvmStatic
    fun stopKeepFull() {
        if (!keepOn) return
        keepOn = false
        LsLog.i(TAG, "全屏保持已停止")
    }
    // ===== 1.4.138：开始导航时补发 =====
    /** 补发节流窗：一次行程里反复开始/结束导航不会刷一堆 service call */
    private const val REAPPLY_GAP_MS = 5 * 60 * 1000L

    @Volatile
    private var appRef: Context? = null

    @Volatile
    private var lastReapplyAt: Long = 0L

    /** 记住 app 上下文，供 requestOnNav 使用 */
    fun remember(ctx: Context) { appRef = ctx.applicationContext }

    /** 后台发一次 16 档：调用点在采样/生命周期线程上，绝不能阻塞 */
    private fun sendAsync(ctx: Context, label: String, threadName: String) {
        val t = Thread {
            runCatching { kotlinx.coroutines.runBlocking { apply(ctx, MODE_FULL) } }
                .onFailure { LsLog.w(TAG, label + "补发失败: " + it.message) }
        }
        t.isDaemon = true
        t.name = threadName
        t.start()
    }

    /**
     * 导航开始时补发。只能覆盖「开始导航」这一刻：方向盘/中控切页会打回 17，
     * 但那不产生导航状态变化 —— 1.4.142 起不再由「卡片重建」补发（会把用户手动
     * 切到其他信息页的档位强行打回全屏导航），只由下一次导航开始兜。
     */
    @JvmStatic
    fun requestOnNav() {
        if (!GesturePrefs.projectionFullOnBoot) return
        val ctx = appRef ?: return
        val now = System.currentTimeMillis()
        if (now - lastReapplyAt < REAPPLY_GAP_MS) return
        lastReapplyAt = now
        sendAsync(ctx, "导航", "ls-nav-proj")
    }

    /** 单测用：清掉节流窗口 */
    fun resetReapplyForTest() {
        lastReapplyAt = 0L
        appRef = null
    }

}
