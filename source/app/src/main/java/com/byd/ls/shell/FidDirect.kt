package com.byd.ls.shell

import android.content.Context
import com.byd.ls.LsApp
import com.byd.ls.cluster.FeatureGate
import com.byd.ls.util.LsLog
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * fid 直读（1.4.118）：一次 app_process 读完 FidCatalog 全部条目。
 *
 * 走 helper 的 `--fids "dev:fid,fid;dev:fid"`，即 autoservice 的 txn 9（getIntArray）；
 * 该 fid 在 txn 9 上返回无效码（-10011/-10013）时，helper 内部再按 txn 5（getInt）
 * 和 txn 7（getFloat）单条重试 —— 智驾电芯电压这类量只有 float 通道有值。
 *
 * 与 VehicleBridge 的差别：不再逐个 getter 反射，也不需要任何 BYDAuto 权限，
 * 129 条参数一次进程内读完（实机 0.37 s）。
 */
object FidDirect {

    private const val TAG = "LsFid"
    private const val OUT_FILE = "/data/local/tmp/ls-fid.txt"
    private const val TIMEOUT_MS = 20_000L
    private const val CACHE_MS = 1_000L

    /** 无效返回码（本固件族的 0xFFF 段，比 BYDAUTO_COMMAND_RESULT_* 更细）。 */
    private val INVALID_RAW = setOf("-10011", "-10013", "-2147482645")

    class Snapshot(
        @JvmField val at: Long,
        @JvmField val values: Map<String, Double>,
        @JvmField val raw: Map<String, String>,
        @JvmField val error: String,
    ) {
        fun value(name: String): Double = values[name] ?: Double.NaN
        fun has(name: String): Boolean = values.containsKey(name)
        override fun toString(): String = "fid@" + at + " n=" + values.size + (if (error.isEmpty()) "" else " err=" + error)
    }

    @Volatile
    var last: Snapshot? = null
        private set

    @Volatile
    var lastError: String = ""
        private set

    /** 成功日志限频：整表 1s 一版，没必要每秒写一行。 */
    private var lastLogAt = 0L

    /** 上一轮快照的来源：app / socket / exec（1.4.133 起 app 域直读优先）。 */
    @Volatile
    var lastVia: String = ""
        private set

    /** dev:fid 反查，用于把原始读数映射回符号名。 */
    private val byAddr: Map<String, FidCatalog.Fid> =
        FidCatalog.all.associateBy { it.dev.toString() + ":" + it.fid }

    @JvmStatic
    fun values(): Map<String, Double> = last?.values ?: emptyMap()

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var running = false

    @Volatile
    private var adasRunning = false
    private var pollThread: Thread? = null
    private var adasThread: Thread? = null
    @Volatile
    private var adasAt = 0L
    @Volatile
    private var adasSoc = Double.NaN
    @Volatile
    private var adasSusp = Double.NaN
    @Volatile
    private var adasFastUntil = 0L

    /** 轮询节拍：1 s 醒一次、缓存 1 s；整表数据燃料/胎压等用这一路。 */
    private const val POLL_MS = 1_000L

    /**
     * 1.4.164：ADAS 保电/云辇专用快通道。
     * 只读 1009:1186988086 和 1023:938475528 两条 fid，300 ms 一版；
     * 不再为了两个状态反复读整张 129 条表，状态切换延迟不再受整表轮询影响。
     */
    private const val ADAS_SPEC = "1009:1186988086;1023:938475528"
    // 1.4.184：稳态 1s 一版，检测到变化后 5s 内切回 300ms 快档。
    // 稳态时 ADAS 两条 fid 的变化概率极低，没必要一直 300ms 打 binder；
    // 用户切换模式后最迟 1s 发现，随后 5s 快档保证连续切换也跟得上。
    private const val ADAS_FAST_MS = 300L
    private const val ADAS_SLOW_MS = 500L
    private const val ADAS_FAST_HOLD_MS = 5_000L
    private const val ADAS_STALE_MS = 2_000L

    @JvmStatic
    fun snapshot(): Snapshot? {
        if (!FeatureGate.card() || !FeatureGate.vehicle()) return null
        start()
        return last
    }

    /**
     * 非阻塞取快照：只唤醒后台轮询线程，立刻返回当前缓存。
     *
     * 曾经的实机故障：在调用线程里 runBlocking 走 ensureDaemon + adblib exec，
     * 通道被 nav_probe 的 screencap 占住时单次阻塞 15 s 以上，把 VehicleData 的刷新线程
     * 锁死（logcat「刷新卡死 15008ms，强制解锁」，胎压还退回 -1）。改后台轮询后永不阻塞。
     */
    @JvmStatic
    fun refresh(ctx: Context?, force: Boolean = false): Snapshot? {
        val c = ctx ?: LsApp.appContext()
        if (c != null && appCtx == null) appCtx = c.applicationContext
        if (!FeatureGate.card() || !FeatureGate.vehicle()) {
            stop()
            return null
        }
        start()
        if (FeatureGate.tyre()) startAdas()
        if (force) {
            val t = Thread({ runCatching { pollOnce() } }, "LsFidOnce")
            t.isDaemon = true
            t.start()
        }
        return last
    }

    /** 1.5.3beta2：油耗/胎压全关时立即停两条 while(true) 线程。 */
    @JvmStatic
    fun stop() {
        running = false
        adasRunning = false
        runCatching { pollThread?.interrupt() }
        runCatching { adasThread?.interrupt() }
        last = null
        adasAt = 0L
        adasSoc = Double.NaN
        adasSusp = Double.NaN
    }

    /**
     * 最近一次 ADAS 快照：`[socSaveMode, suspensionMode]`，任一无效为 NaN。
     * 超过 [ADAS_STALE_MS] 没更新就返回 null，调用方不覆盖已有状态。
     */
    @JvmStatic
    fun adasValues(): DoubleArray? {
        if (!FeatureGate.card() || !FeatureGate.tyre()) return null
        val at = adasAt
        if (at == 0L || System.currentTimeMillis() - at > ADAS_STALE_MS) return null
        return doubleArrayOf(adasSoc, adasSusp)
    }

    private fun startAdas() {
        if (!FeatureGate.card() || !FeatureGate.tyre() || adasRunning) return
        synchronized(this) {
            if (!FeatureGate.card() || !FeatureGate.tyre() || adasRunning) return
            adasRunning = true
            // 首次启动先给 5s 快档：冷启动/唤醒后尽快拿到第一版状态
            adasFastUntil = System.currentTimeMillis() + ADAS_FAST_HOLD_MS
            val t = Thread({
                while (adasRunning && FeatureGate.card() && FeatureGate.tyre()) {
                    runCatching { pollAdasOnce() }.onFailure { LsLog.w(TAG, "adas poll: " + it.message) }
                    if (!adasRunning) break
                    val sleep = if (System.currentTimeMillis() < adasFastUntil) ADAS_FAST_MS else ADAS_SLOW_MS
                    try {
                        Thread.sleep(sleep)
                    } catch (ignored: InterruptedException) {
                        if (!adasRunning) break
                    }
                }
                adasRunning = false
                adasThread = null
            }, "LsFidAdas")
            adasThread = t
            t.isDaemon = true
            t.start()
        }
    }

    private fun pollAdasOnce() {
        if (!FeatureGate.card() || !FeatureGate.tyre() || appCtx == null) return
        val text = BydDirect.fidsTextApp(ADAS_SPEC).orEmpty()
        if (text.isEmpty() || text.startsWith("ERR")) return
        var soc = Double.NaN
        var susp = Double.NaN
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val addr = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            val fid = byAddr[addr] ?: continue
            val d = decode(fid, v) ?: continue
            when (fid.name) {
                "socSaveMode" -> if (d >= 0.0 && d <= 3.0) soc = d
                "suspensionMode" -> if (d >= 1.0 && d <= 2.0) susp = d
            }
        }
        if (soc.isNaN() && susp.isNaN()) return
        val changed = (!soc.isNaN() && soc != adasSoc) || (!susp.isNaN() && susp != adasSusp)
        if (!soc.isNaN()) adasSoc = soc
        if (!susp.isNaN()) adasSusp = susp
        adasAt = System.currentTimeMillis()
        if (changed) {
            adasFastUntil = System.currentTimeMillis() + ADAS_FAST_HOLD_MS
            LsLog.i(TAG, "adas fast socSave=" + soc + " susp=" + susp)
        }
    }

    private fun start() {
        if (!FeatureGate.card() || !FeatureGate.vehicle() || running) return
        synchronized(this) {
            if (!FeatureGate.card() || !FeatureGate.vehicle() || running) return
            running = true
            val t = Thread({
                while (running && FeatureGate.card() && FeatureGate.vehicle()) {
                    runCatching { pollOnce() }.onFailure { LsLog.w(TAG, "poll: " + it.message) }
                    if (!running) break
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (ignored: InterruptedException) {
                        if (!running) break
                    }
                }
                running = false
                pollThread = null
            }, "LsFidPoll")
            pollThread = t
            t.isDaemon = true
            t.start()
        }
    }

    private fun pollOnce() {
        if (!FeatureGate.card() || !FeatureGate.vehicle()) return
        val ctx = appCtx ?: return
        val prev = last
        if (prev != null && System.currentTimeMillis() - prev.at < CACHE_MS) return
        val text = runBlocking { fetchText(ctx) }.orEmpty()
        if (text.isEmpty() || text.startsWith("ERR")) {
            lastError = "空输出 " + text.take(80) + " / " + HelperBridge.lastError
            LsLog.w(TAG, lastError)
            return
        }
        val raw = LinkedHashMap<String, String>()
        val vals = LinkedHashMap<String, Double>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val addr = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            raw[addr] = v
            val fid = byAddr[addr] ?: continue
            val d = decode(fid, v) ?: continue
            vals[fid.name] = d
        }
        // SOC 兜底：1014 那条偶尔在整车未上电时无值，充电域那条（1009）可补。
        if (!vals.containsKey("soc")) vals["socCharging"]?.let { vals["soc"] = it }
        val snap = Snapshot(System.currentTimeMillis(), vals, raw, "")
        last = snap
        lastError = ""
        val now = System.currentTimeMillis()
        if (now - lastLogAt >= 30_000L) {
            lastLogAt = now
            LsLog.i(TAG, "ok n=" + vals.size + "/" + raw.size + " via=" + lastVia + " " + describe())
        }
    }

    /** 与 helper allSpec() 同一拼法；FidTable 已搬到 app 源集，两侧共用同一份。 */
    private fun allSpec(): String {
        val sb = StringBuilder()
        for (row in com.byd.ls.helper.FidTable.ALL) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(row[0]).append(':')
            for (i in 1 until row.size) {
                if (i > 1) sb.append(',')
                sb.append(row[i])
            }
        }
        return sb.toString()
    }

    /**
     * 1.4.133：app 域直读优先 → helper socket → 一次性 app_process。
     *
     * app 域这条路是第 10 条的落地：getInstance 传 BydPermissionContext 之后，
     * txn 9 在 app 进程内与 shell 域回包等价（实测 app `ints=0,3,72,126,270`
     * vs shell `ints=0,3,71,125,270`），服务端不按 uid 校验签名权限。
     * 于是整张 129 条表变成几次进程内 binder 调用，不再需要 helper dex、
     * adblib 通道、一次性 app_process，也不再需要每次改表 push ls-helper.apk。
     */
    private suspend fun fetchText(appCtx: Context): String? {
        val viaApp = try {
            BydDirect.fidsTextApp(allSpec())
        } catch (t: Throwable) {
            LsLog.w(TAG, "app 域直读异常: " + t.message)
            null
        }
        if (viaApp != null && viaApp.isNotEmpty() && !viaApp.startsWith("ERR")) {
            lastVia = "app"
            return viaApp
        }
        // 回退 1：常驻 helper 的 abstract socket（"fidsall" 一行命令）。
        // 不占 adblib 通道 —— nav_probe 的 screencap 一超时就会关通道并静默 30/60 s
        //（docs/13 §4.1），fid 读会被连带饿死；socket 走常驻进程内的 binder，互不相干。
        val viaSocket = try {
            HelperBridge.socketQueryMulti(appCtx, "fidsall").orEmpty()
        } catch (t: Throwable) {
            ""
        }
        if (viaSocket.isNotEmpty() && !viaSocket.startsWith("ERR")) {
            lastVia = "socket"
            return viaSocket
        }
        // 回退：一次性 app_process（要先确认 dex 在车上）。
        if (!HelperBridge.ensureDexOnly(appCtx)) {
            lastError = "dex 未就绪：" + HelperBridge.lastError
            return null
        }
        val one = execOnce(appCtx)
        if (one != null && one.isNotEmpty() && !one.startsWith("ERR")) lastVia = "exec"
        return one
    }

    /** 一次性进程读法（常驻 socket 不可用时的兜底）。 */
    private suspend fun execOnce(appCtx: Context): String? {
        val cmd = "sh -c 'env CLASSPATH=" + HelperBridge.tmpDexPath() + " app_process /system/bin " +
            "com.byd.ls.helper.HelperMain --fidsall > " + OUT_FILE + " 2>&1'; cat " + OUT_FILE
        val r = try {
            ShellManager.exec(appCtx, cmd, TIMEOUT_MS)
        } catch (t: Throwable) {
            LsLog.w(TAG, "exec: " + t.message)
            return null
        }
        val first = r.stdout.trim()
        if (first.isNotEmpty() && !first.startsWith("ERR")) return first
        // 通道偶尔把组合命令的回读丢掉；helper 已把结果落盘，单独 cat 一次即可救回。
        val again = try {
            ShellManager.exec(appCtx, "cat " + OUT_FILE, TIMEOUT_MS)
        } catch (t: Throwable) {
            null
        }
        return again?.stdout?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("ERR") }
    }

    @Suppress("unused")
    private fun buildSpecUnused(): String {
        val byDev = LinkedHashMap<Int, MutableList<Int>>()
        for (f in FidCatalog.all) byDev.getOrPut(f.dev) { ArrayList() }.add(f.fid)
        val sb = StringBuilder()
        for ((dev, fids) in byDev) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(dev).append(':')
            sb.append(fids.joinToString(","))
        }
        return sb.toString()
    }

    /** 原始串 → 显示值；无效返回 null（该 fid 本车不支持或此刻无值）。 */
    private fun decode(fid: FidCatalog.Fid, v: String): Double? {
        if (v.isEmpty() || INVALID_RAW.contains(v)) return null
        if (v.endsWith("f")) {
            val f = v.dropLast(1).toDoubleOrNull() ?: return null
            // txn 7 对不支持的 fid 一律回 -1.0，与真实负值无法区分，按无值处理。
            if (f == -1.0) return null
            return if (fid.kind == FidCatalog.Kind.INT_SCALED) f * fid.scale + fid.offset else f
        }
        val i = v.toDoubleOrNull() ?: return null
        return when (fid.kind) {
            FidCatalog.Kind.INT_SCALED -> i * fid.scale + fid.offset
            FidCatalog.Kind.INT_TEMP_C_OFS40 -> i - 40.0
            FidCatalog.Kind.INT_KPA -> i
            FidCatalog.Kind.FLOAT_PERCENT, FidCatalog.Kind.FLOAT_KWH,
            FidCatalog.Kind.FLOAT_KW, FidCatalog.Kind.FLOAT_VOLT,
            FidCatalog.Kind.FLOAT_AMP, FidCatalog.Kind.FLOAT_LITERS -> i
            else -> i + fid.offset
        }
    }

    /** 调试用：一行摘要，便于 adb logcat / 卡片角落观察。 */
    @JvmStatic
    fun describe(): String {
        val s = last ?: return "fid=null " + lastError
        val sb = StringBuilder(String.format(Locale.US, "n=%d", s.values.size))
        for (k in listOf("soc", "mileage", "evRangeKm", "tirePressFL", "tirePressFR", "insideTemp")) {
            if (s.has(k)) sb.append(' ').append(k).append('=').append(String.format(Locale.US, "%.1f", s.value(k)))
        }
        // 1.4.135：云辇C 模式是枚举（1 舒适 / 2 运动），用整数打，便于复验时一眼看出当前是哪一档。
        if (s.has("suspensionMode")) sb.append(" susp=").append(String.format(Locale.US, "%.0f", s.value("suspensionMode")))
        return sb.toString()
    }
}
