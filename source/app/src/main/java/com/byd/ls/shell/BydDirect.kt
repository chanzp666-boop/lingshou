package com.byd.ls.shell

import android.content.Context
import com.byd.ls.util.LsLog
import java.lang.reflect.Method

/**
 * App 进程内直接反射 BYD framework 设备类（1.4.23）。
 *
 * 为什么不再走 helper/app_process：实测（docs/11 §4）第三方 App 经 adbd 在 DiLink 上
 * 跑 `app_process` 会挂住（三种 classpath 写法全部超时、零输出、无 avc），启迪能跑是因为
 * 它是预装 launcher（非 adbd 路径）。类本身在 **bootclasspath（framework.jar）**，
 * 所以 App 进程可以直接 `Class.forName` —— 唯一障碍是 Android 10 的 hidden API 限制，
 * 用 `VMRuntime.setHiddenApiExemptions({"L"})` 解除（API 28+ 通用做法，VMRuntime 在灰名单可反射）。
 *
 * 若 BYD 的 JNI 层校验调用者 uid（只允许 system），这里会抛异常并回退自算，
 * 此时再走 binder 逆向（libbydautoservice.so，见 docs/11 §3）。
 */
object BydDirect {

    // 1.4.94/1.4.95: EVT 日志降频（见回调内注释）
    private val LAST_EVT_AT = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private const val TAG = "LsByd"
    private val DEVICE_PKG = mapOf(
        "ac" to "ac.BYDAutoAcDevice",
        "bodywork" to "bodywork.BYDAutoBodyworkDevice",
        "charging" to "charging.BYDAutoChargingDevice",
        "engine" to "engine.BYDAutoEngineDevice",
        "gearbox" to "gearbox.BYDAutoGearboxDevice",
        "instrument" to "instrument.BYDAutoInstrumentDevice",
        "light" to "light.BYDAutoLightDevice",
        "multimedia" to "multimedia.BYDAutoMultimediaDevice",
        "setting" to "setting.BYDAutoSettingDevice",
        "statistic" to "statistic.BYDAutoStatisticDevice",
        "tyre" to "tyre.BYDAutoTyreDevice"
    )

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastRaw: String = ""
        private set
    @Volatile private var exemptionDone = false
    @Volatile var available: Boolean = false
        private set

    private val instances = HashMap<String, Any?>()

    /** 解除 hidden API 限制（必须在首次加载目标类之前调用；多次调用安全） */
    @Synchronized
    fun bypassHiddenApi(): Boolean {
        if (exemptionDone) return available
        exemptionDone = true
        try {
            val vmClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime: Method = vmClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null) ?: throw IllegalStateException("VMRuntime null")
            val setExemptions = vmClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExemptions.isAccessible = true
            setExemptions.invoke(runtime, arrayOf("L"))
            available = true
            LsLog.i(TAG, "hidden API 豁免已设置（L）")
        } catch (t: Throwable) {
            lastError = "豁免失败：" + (t.cause?.message ?: t.message ?: t.javaClass.simpleName)
            LsLog.w(TAG, lastError)
            available = false
        }
        return available
    }

    /**
     * 传进去的 Context 决定 getter 里那条 `enforceCallingOrSelfPermission` 会不会拦我们：
     * 设备类把 getInstance 的入参存成 mContext，权限断言在本地执行 → 换成 [BydPermissionContext] 即放行。
     * 1.4.132 起默认这样做；若服务端仍按 uid 校验（抛 SecurityException），byd.compare 会当场暴露。
     */
    private fun instance(ctx: Context, device: String): Any? {
        instances[device]?.let { return it }
        val suffix = DEVICE_PKG[device] ?: return null
        val cls = Class.forName("android.hardware.bydauto.$suffix")
        val getInstance = cls.getMethod("getInstance", Context::class.java)
        @Suppress("UNCHECKED_CAST")
        val inst = getInstance.invoke(null, BydPermissionContext(ctx))
        if (inst != null) instances[device] = inst
        return inst
    }

    /**
     * 批量读：`read(ctx, "statistic", "getLast50KmEqualFuelConValue,getLastFuelConPHMValue")`
     * 支持 `getMileageNumber:0` 单 int 参数形式。返回 method → 原始字符串（含 "ERR …"）。
     */
    @JvmStatic
    fun read(ctx: Context, device: String, methods: String): Map<String, String>? {
        if (!bypassHiddenApi()) return null
        return try {
            val inst = instance(ctx, device) ?: run {
                lastError = "实例为空（$device）"
                return null
            }
            val out = LinkedHashMap<String, String>()
            for (raw in methods.split(",")) {
                val spec = raw.trim()
                if (spec.isEmpty()) continue
                val colon = spec.indexOf(':')
                val v = try {
                    val m: Method
                    val res: Any?
                    if (colon > 0) {
                        m = inst.javaClass.getMethod(spec.substring(0, colon), Int::class.javaPrimitiveType)
                        res = m.invoke(inst, spec.substring(colon + 1).trim().toInt())
                    } else {
                        m = inst.javaClass.getMethod(spec)
                        res = m.invoke(inst)
                    }
                    when (res) {
                        is Double -> String.format(java.util.Locale.US, "%.4f", res)
                        else -> res?.toString() ?: "null"
                    }
                } catch (t: Throwable) {
                    val c = (t.cause ?: t)
                    "ERR " + c.javaClass.simpleName + ": " + (c.message ?: "").take(60)
                }
                out[spec] = v
            }
            lastRaw = out.entries.joinToString(";") { it.key + "=" + it.value }
            lastError = ""
            out
        } catch (t: Throwable) {
            val c = (t.cause ?: t)
            lastError = c.javaClass.simpleName + ": " + (c.message ?: "").take(120)
            LsLog.w(TAG, "read($device) 失败：$lastError")
            null
        }
    }

    @Volatile private var listenerRegistered = false
    private val bound = java.util.HashMap<String, Pair<Any, Any>>()   // dev -> (device, listener)

    /**
     * 在 **App 进程内**注册 BYDAuto 监听（1.4.38，与迪友桌面同架构）。
     *
     * 依据 docs/11 §11：`registerListener(IBYDAutoListener)` 与回调 `onDataChanged` 没有权限检查，
     * 而各 getter 里有。迪友没有任何 BYD 权限却能读油耗/胎压，靠的就是这条推送路径。
     * 监听必须挂在常驻进程里（一次性 app_process 注册完就退出，收不到推送）。
     */
    @JvmStatic
    fun registerStatisticListener(ctx: Context) {
        if (listenerRegistered) return
        listenerRegistered = true
        // 三个设备都要注册：油耗/电耗在 statistic，**仪表显示值（含胎压/油耗显示）在 instrument**，
        // 胎压原值在 tyre。只注册 statistic 会拿不到油耗与胎压（docs/11 §14）。
        for (dev in listOf("statistic", "instrument", "tyre")) {
            runCatching { registerOne(ctx, dev) }
                .onFailure { LsLog.w(TAG, "注册 $dev 监听失败: ${it.message}") }
        }
        // 1.4.49：**具名回调**通道。Proxy 只拿得到 onDataChanged（实测 value/dval 恒 0），
        // 迪友 com.dy.launcher 的 a4.m 是 extends AbsBYDAutoStatisticListener 才拿到
        // onLastFuelConPHMChanged(double) 这类具名回调 —— 见 docs/12 §9 的 REA 证据。
        runCatching {
            TypedStatListener.setSink { k, v -> appendEvent(ctx, "typed $k=$v") }
            appendEvent(ctx, "typed " + TypedStatListener.attach(ctx))
            appendEvent(ctx, "typed " + TypedStatListener.attachTyre(ctx))
        }.onFailure { LsLog.w(TAG, "typed listener 注册失败: ${it.message}") }
    }

    /**
     * 1.4.71：**重注册拉快照**。BYD 设备在 registerListener 时会投一次当前值，
     * 迪友"一上车就有数"就是靠这个（它从不重启，一直握着这份快照）。
     * 我们每次装包/重启都是新注册 → 只能等下一次变化。重注册 = 主动要一次快照。
     * 不缓存、不回灌历史：拿不到就一直显示 --。
     */
    @JvmStatic
    fun resync(): String {
        val sb = StringBuilder()
        for (dev in listOf("statistic", "instrument", "tyre")) {
            val pair = bound[dev] ?: continue
            val (device, listener) = pair
            runCatching {
                val cls = device.javaClass
                val lIf = Class.forName("android.hardware.IBYDAutoListener")
                cls.getMethod("unregisterListener", lIf).invoke(device, listener)
                cls.getMethod("registerListener", lIf).invoke(device, listener)
            }.onFailure { sb.append(dev).append("=ERR ").append(it.message).append(';') }
                .onSuccess { sb.append(dev).append("=ok;") }
        }
        if (sb.isEmpty()) return "no-bound"
        return sb.toString()
    }

    private fun registerOne(ctx: Context, devShort: String) {
        try {
            if (!bypassHiddenApi()) {
                LsLog.w(TAG, "注册监听失败：hidden API 豁免未成功")
                return
            }
            val cap = when (devShort) {
                "statistic" -> "statistic.BYDAutoStatisticDevice"
                "instrument" -> "instrument.BYDAutoInstrumentDevice"
                "tyre" -> "tyre.BYDAutoTyreDevice"
                else -> return
            }
            val devCls = Class.forName("android.hardware.bydauto.$cap")
            val dev = devCls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            if (dev == null) { LsLog.w(TAG, "$devShort 实例为空"); return }
            val lIf = Class.forName("android.hardware.IBYDAutoListener")
            val handler = java.lang.reflect.InvocationHandler { _, m, args ->
                if (m.name == "onDataChanged" && args != null && args.size == 1) {
                    val ev = args[0]
                    if (ev != null) {
                        runCatching {
                            val evT = ev.javaClass.getMethod("getEventType").invoke(ev) as Int
                            val v = ev.javaClass.getMethod("getValue").invoke(ev) as Int
                            val dv = ev.javaClass.getMethod("getDoubleValue").invoke(ev) as Double
                            // 1.4.94/1.4.95: 按事件类型限速 —— 每个 eventType 每 5 秒最多记一条日志。
                            // 原来 315621436 每 60ms 一条(值 0/1 交替)，23 秒就把 ls.log 冲掉 500 行，
                            // 把 a11y 诊断全埋了；按类型限速后单类型最多 12 条/分钟。
                            val nowMs = android.os.SystemClock.elapsedRealtime()
                            val lastAt = LAST_EVT_AT.get(evT) ?: 0L
                            if (nowMs - lastAt > 5_000L) {
                                LAST_EVT_AT[evT] = nowMs
                                LsLog.i(TAG, "EVT event=$evT value=$v dval=$dv")
                            }
                            appendEvent(ctx, "$devShort event=$evT value=$v dval=$dv")
                            // 1.4.52：回调即入内存（不靠日志尾巴，油耗事件 10 小时才 1 条）
                            com.byd.ls.cluster.data.VehicleData.onNativeEvent(evT, v.toDouble(), dv)
                        }
                    }
                }
                when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Double::class.javaPrimitiveType -> 0.0
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
            val l = java.lang.reflect.Proxy.newProxyInstance(lIf.classLoader, arrayOf(lIf), handler)
            devCls.getMethod("registerListener", lIf).invoke(dev, l)
            bound[devShort] = dev to l
            LsLog.i(TAG, "$devShort listener 已注册（App 进程内）")
            appendEvent(ctx, "registered $devShort @${System.currentTimeMillis()}")
        } catch (t: Throwable) {
            val c = t.cause ?: t
            LsLog.w(TAG, "$devShort registerListener 失败：" + c.javaClass.simpleName + ": " + c.message)
            appendEvent(ctx, "register $devShort ERR ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // 1.4.107：native-events.log 只增不减（实测涨到 56 MB）→ 加硬上限，超了裁到最近 4 MB。
    // 每 500 次追加才 stat 一次，避免高频回调里反复取文件属性；裁剪用 tmp+rename，不丢窗口内事件。
    private const val EVT_LOG_MAX = 8L * 1024 * 1024
    private const val EVT_LOG_KEEP = 4L * 1024 * 1024
    private val evtAppends = java.util.concurrent.atomic.AtomicInteger(0)

    // 1.4.184：高频事件不再每条 open/append/close 一次文件；常驻 writer，写一行 flush 一行。
    // flush 只把用户态缓冲交给内核，不会丢事件；裁剪时先关 writer 再替换文件。
    private var evtWriter: java.io.BufferedWriter? = null
    private var evtFileRef: java.io.File? = null

    @Synchronized
    private fun appendEvent(ctx: Context, line: String) {
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = java.io.File(dir, "native-events.log")
            if (evtWriter == null || evtFileRef != f) {
                closeEventWriter()
                evtWriter = java.io.BufferedWriter(java.io.FileWriter(f, true))
                evtFileRef = f
            }
            val w = evtWriter ?: return@runCatching
            w.write(line)
            w.newLine()
            w.flush()
            if (evtAppends.incrementAndGet() % 500 == 0 && f.length() > EVT_LOG_MAX) {
                closeEventWriter()
                trimEventLog(f)
            }
        }
    }

    /** 进程进入后台/内存回收前调用，确保缓冲不留在用户态。 */
    @JvmStatic
    fun flushEventLog() {
        synchronized(this) {
            runCatching { evtWriter?.flush() }
        }
    }

    private fun closeEventWriter() {
        runCatching { evtWriter?.flush() }
        runCatching { evtWriter?.close() }
        evtWriter = null
        evtFileRef = null
    }

    /** 裁剪到最近 EVT_LOG_KEEP 字节（先写 tmp 再改名替换） */
    private fun trimEventLog(f: java.io.File) {
        runCatching {
            val len = f.length()
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek((len - EVT_LOG_KEEP).coerceAtLeast(0L))
                val buf = ByteArray(EVT_LOG_KEEP.toInt())
                val n = raf.read(buf)
                if (n <= 0) return@use
                val tmp = java.io.File(f.parentFile, "native-events.tmp")
                java.io.FileOutputStream(tmp).use { it.write(buf, 0, n) }
                if (!f.delete() || !tmp.renameTo(f)) tmp.delete()
            }
            LsLog.w(TAG, "native-events.log 超 " + (EVT_LOG_MAX / 1048576) + "MB，已裁剪到最近 " + (EVT_LOG_KEEP / 1048576) + "MB")
        }
    }

    /** 导出 BYDAutoFeatureIds 全部静态常量（name=value），用于把 event 号映射成物理量。 */
    @JvmStatic
    fun dumpFeatureIds(): String {
        if (!bypassHiddenApi()) return "ERR 豁免失败"
        return try {
            val c = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
            val sb = StringBuilder()
            for (f in c.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (!f.type.isPrimitive && f.type != String::class.java) continue
                runCatching {
                    f.isAccessible = true
                    val v = f.get(null)
                    if (sb.isNotEmpty()) sb.append(';')
                    sb.append(f.name).append('=').append(v)
                }
            }
            sb.toString()
        } catch (t: Throwable) {
            val c = t.cause ?: t
            "ERR " + c.javaClass.simpleName + ": " + (c.message ?: "")
        }
    }

    /**
     * 第二档第 10 条的开关：app 域直读优先，省掉一次 app_process 与 adblib 通道占用。
     *
     * 实测依据（2026-11-12 23:21，1.4.131+探针，DiLink 4.0）：
     * - shell 域（uid 2000）走 statistic getter 被拒：
     *   `getLastFuelConPHMValue=ERR SecurityException: Neither user 2000 nor current process has
     *   android.permission.BYDAUTO_STATISTIC_GET.`
     * - app 域（uid 10084，getInstance 传 BydPermissionContext）全通且与仪表一致：
     *   getLast50KmEqualFuelConValue=4.5000 / getTotalMileageValue=48699 / getElecPercentageValue=72.0000
     * 所以 app 域不是「省一次进程」这么简单，它在 statistic 上是**唯一**能走 getter 的一侧。
     * 连续 3 次失败在本进程内永久回退 helper，不会把读数拖死。
     */
    @JvmStatic var preferAppDomain: Boolean = true

    /** 只在 getter 路径实测通过的设备上启用；未验证的设备一律仍走 helper。 */
    @JvmStatic var appDevices: Set<String> = setOf("statistic")

    @Volatile private var appDead = false
    private var appFails = 0

    /** app 域直读；不可用 / 异常 / 含 ERR 一律返回 null，让调用方回退 helper。 */
    @JvmStatic
    fun quickRead(ctx: Context, device: String, methods: String): Map<String, String>? {
        if (!preferAppDomain || appDead || !appDevices.contains(device)) return null
        val m = try {
            read(ctx, device, methods)
        } catch (t: Throwable) {
            lastError = "直读异常：" + (t.cause?.message ?: t.message ?: t.javaClass.simpleName)
            null
        }
        if (m == null || m.isEmpty() || m.values.any { it.trim().startsWith("ERR") }) {
            if (++appFails >= 3) {
                appDead = true
                LsLog.w(TAG, "app 域直读连续失败 3 次，本进程内回退 helper：" + lastError.take(80))
            }
            return null
        }
        appFails = 0
        return m
    }

    /**
     * app 域直连 autoservice 的 txn 9（getIntArray）——与 helper 的 `--binder9` 同一条路
     * （服务名 autoservice、token android.gui.BYDAutoServer），差别只在调用方 uid 是 app(10084)。
     * 回包格式保持 helper 口径：`ok=<bool> size=<n> ints=<a,b,c…>`，便于两路直接对照。
     * 过不过取决于服务端是否按 uid 校验；`byd.binder9` 会把 app / shell 两路并排返回。
     */
    @JvmStatic
    fun binder9(dev: Int, fids: IntArray): String {
        return try {
            bypassHiddenApi()
            val sm = Class.forName("android.os.ServiceManager")
            val gs = sm.getDeclaredMethod("getService", String::class.java)
            gs.isAccessible = true
            val b = gs.invoke(null, "autoservice") as? android.os.IBinder
                ?: return "ERR autoservice 不存在"
            val d = android.os.Parcel.obtain()
            val r = android.os.Parcel.obtain()
            try {
                d.writeInterfaceToken("android.gui.BYDAutoServer")
                d.writeInt(dev)
                d.writeInt(fids.size)
                for (f in fids) d.writeInt(f)
                val ok = b.transact(9, d, r, 0)
                val size = r.dataSize()
                val cnt = Math.min(size / 4, 12)
                val sb = StringBuilder("ok=" + ok + " size=" + size + " ints=")
                var i = 0
                while (i < cnt) {
                    sb.append(r.readInt()).append(',')
                    i++
                }
                sb.toString()
            } finally {
                d.recycle()
                r.recycle()
            }
        } catch (t: Throwable) {
            var e: Throwable = t
            while (e.cause != null && e.cause !== e) e = e.cause!!
            "ERR " + e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    // ── 第 10 条：整张 fid 表的 app 域直读（与 helper --fidsall 逐行同格式） ──

    /** 缓存 autoservice 句柄，避免每轮 3s 轮询都反射一次 ServiceManager。 */
    @Volatile private var autoBinder: android.os.IBinder? = null

    private fun autoService(): android.os.IBinder? {
        val c = autoBinder
        if (c != null && c.isBinderAlive) return c
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val gs = sm.getDeclaredMethod("getService", String::class.java)
            gs.isAccessible = true
            (gs.invoke(null, "autoservice") as? android.os.IBinder)?.also { autoBinder = it }
        } catch (t: Throwable) {
            null
        }
    }

    /** 与 helper isInvalidFid 同一套哨兵（0xFFF 段，不是 BYDAUTO_COMMAND_RESULT_*）。 */
    private fun invalidFid(v: Int): Boolean =
        v == -10011 || v == -10013 || v == -2147482645 || v == Int.MIN_VALUE

    /** txn 9 = getIntArray：回包 `result, count, value…`。 */
    private fun txn9All(b: android.os.IBinder, dev: Int, fids: IntArray): IntArray? {
        val d = android.os.Parcel.obtain()
        val r = android.os.Parcel.obtain()
        try {
            d.writeInterfaceToken("android.gui.BYDAutoServer")
            d.writeInt(dev)
            d.writeInt(fids.size)
            for (f in fids) d.writeInt(f)
            if (!b.transact(9, d, r, 0)) return null
            r.readInt()
            val n = r.readInt()
            if (n <= 0) return null
            val out = IntArray(n)
            var i = 0
            while (i < n && r.dataAvail() >= 4) {
                out[i] = r.readInt()
                i++
            }
            return out
        } catch (t: Throwable) {
            return null
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    /** txn 5（getInt）/ txn 7（getFloat）：回包 `result, value`。 */
    private fun txnOne(b: android.os.IBinder, code: Int, dev: Int, fid: Int): Int {
        val d = android.os.Parcel.obtain()
        val r = android.os.Parcel.obtain()
        try {
            d.writeInterfaceToken("android.gui.BYDAutoServer")
            d.writeInt(dev)
            d.writeInt(1)
            d.writeInt(fid)
            if (!b.transact(code, d, r, 0)) return Int.MIN_VALUE
            r.readInt()
            return if (r.dataAvail() >= 4) r.readInt() else Int.MIN_VALUE
        } catch (t: Throwable) {
            return Int.MIN_VALUE
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    /** 单条兜底：txn 5 取 int → txn 7 取 float（位模式还原），口径与 helper readSingle 一致。 */
    private fun readSingle(b: android.os.IBinder, dev: Int, fid: Int): String? {
        val r5 = txnOne(b, 5, dev, fid)
        if (!invalidFid(r5)) return r5.toString()
        val r7 = txnOne(b, 7, dev, fid)
        if (!invalidFid(r7)) return String.format(java.util.Locale.US, "%.4ff", java.lang.Float.intBitsToFloat(r7))
        return null
    }

    /**
     * app 域读整张 fid 表。spec 语法与 helper `--fids` 完全一致（`dev:fid,fid;dev2:…`），
     * 输出与 helper `--fidsall` 逐行同格式（`dev:fid=值`，float 带 4 位小数与 f 后缀），
     * 所以 FidDirect 的解析器两条路共用。
     *
     * 实测依据（2026-11-12 23:41，DiLink 4.0，1.4.132）：app 域 txn 9 与 shell 域回包等价
     * （app `ints=0,3,72,126,270` vs shell `ints=0,3,71,125,270`，差值只是车在放电），
     * 服务端不按 uid 校验签名权限 → 整张表可以搬进 app 进程，不必再起 app_process。
     * 返回 null 时调用方回退 helper。
     */
    @JvmStatic
    fun fidsTextApp(spec: String): String? {
        if (!preferAppDomain || appDead) return null
        bypassHiddenApi()
        val b = autoService() ?: run {
            lastError = "autoservice 不存在"
            return null
        }
        val sb = StringBuilder()
        var groups = 0
        for (raw in spec.split(";")) {
            val g = raw.trim()
            if (g.isEmpty()) continue
            val c = g.indexOf(':')
            if (c <= 0) continue
            val dev = g.substring(0, c).trim().toIntOrNull() ?: continue
            val fids = g.substring(c + 1).split(",")
                .mapNotNull { it.trim().toIntOrNull() }.toIntArray()
            if (fids.isEmpty()) continue
            val vals = txn9All(b, dev, fids)
            if (vals == null) {
                sb.append(dev).append(":ERR\n")
                continue
            }
            groups++
            for (i in fids.indices) {
                val v = if (i < vals.size) vals[i] else Int.MIN_VALUE
                if (invalidFid(v)) {
                    val alt = readSingle(b, dev, fids[i])
                    if (alt != null) {
                        sb.append(dev).append(':').append(fids[i]).append('=').append(alt).append('\n')
                        continue
                    }
                }
                sb.append(dev).append(':').append(fids[i]).append('=').append(v).append('\n')
            }
        }
        if (groups == 0) {
            lastError = "txn9 全组失败（autoservice 拒答）"
            if (++appFails >= 3) {
                appDead = true
                LsLog.w(TAG, "app 域 fid 直读连续失败 3 次，本进程内回退 helper")
            }
            return null
        }
        appFails = 0
        return sb.toString()
    }

    fun statusText(): String = when {
        lastError.isNotEmpty() -> "失败：" + lastError.take(120)
        lastRaw.isNotEmpty() -> lastRaw.take(120)
        available -> "就绪"
        else -> "未初始化"
    }
}
