package com.byd.ls.shell

import android.content.Context
import com.byd.ls.util.LsLog
import kotlinx.coroutines.runBlocking

/**
 * BYD 原生的车辆数据桥（1.4.18）。
 *
 * 一切车辆量都走 `android.hardware.bydauto.<module>.BYDAuto*Device` 的反射调用，
 * helper 一次 `--batch` 读完多个 getter，避免为每个量起一次 app_process。
 * **不再有任何第三方 App 依赖**（迪加/迪友已从代码里删除）。
 *
 * 设备短名 → 类：ac / bodywork / charging / engine / gearbox / instrument /
 *               light / multimedia / setting / statistic / tyre / pm2p5
 */
object VehicleBridge {

    private const val TAG = "LsVehicle"

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastRaw: String = ""
        private set
    @Volatile var lastExit: Int = 0
        private set

    /**
     * 批量读取：`batch(ctx, "statistic", "getTotalFuelConValue,getTotalMileageValue")`
     * 返回 method → 原始字符串（含 "ERR …"）；整体失败返回 null。
     */
    @JvmStatic
    fun batch(ctx: Context, device: String, methods: String): Map<String, String>? = runBlocking {
        try {
            // 第 10 条：app 域直读优先（默认关；连续失败自动回退，见 BydDirect.quickRead）
            BydDirect.quickRead(ctx, device, methods)?.let { app ->
                lastRaw = app.entries.joinToString(";") { it.key + "=" + it.value }
                lastExit = 0
                lastError = ""
                LsLog.i(TAG, "app 域直读 " + app.size + " 项（$device）")
                return@runBlocking app
            }
            if (!HelperBridge.ensureDexOnly(ctx)) {
                lastError = "dex 未就绪：" + HelperBridge.lastError
                return@runBlocking null
            }
            // 关键：app_process 的 VM 会继承 stdout，adbd 等不到流关闭 → exec 超时。
            // 所以外层套 sh -c 并把输出落文件，再 cat 出来（迪加/启迪/DiStyle 的 helper
            // 都以 shell(2000) 跑通，见 docs/11 §7 的 ps 证据）。
            val outFile = "/data/local/tmp/ls-v.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --batch $device $methods > $outFile 2>&1'; cat $outFile"
            val r = ShellManager.exec(ctx, cmd, 25_000L)
            val text = r.stdout.trim()
            lastRaw = text
            lastExit = r.exitCode
            if (text.isEmpty()) {
                lastError = "空输出 exit=${r.exitCode}"
                return@runBlocking null
            }
            if (text.startsWith("ERR") || text.contains("Exception") || text.contains("Error")) {
                lastError = text.take(160)
                return@runBlocking null
            }
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            val out = LinkedHashMap<String, String>()
            for (pair in line.split(";")) {
                val i = pair.indexOf('=')
                if (i > 0) out[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
            }
            if (out.isEmpty()) {
                lastError = "无法解析：" + line.take(80)
                return@runBlocking null
            }
            out
        } catch (t: Throwable) {
            lastError = "batch 异常：" + (t.message ?: t.javaClass.simpleName)
            LsLog.w(TAG, lastError)
            null
        }
    }

    /** 解析数值；"ERR …" 或非法返回 NaN。 */
    @JvmStatic
    fun num(map: Map<String, String>?, method: String): Double {
        val v = map?.get(method) ?: return Double.NaN
        if (v.startsWith("ERR")) return Double.NaN
        return v.toDoubleOrNull() ?: Double.NaN
    }

    /** 列出某设备所有以 get 开头的方法（名称/参数个数），用于零猜测确认方法名。 */
    @JvmStatic
    fun list(ctx: Context, device: String): String = runBlocking {
        try {
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪：" + HelperBridge.lastError
            val outFile = "/data/local/tmp/ls-v.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --list $device > $outFile 2>&1'; cat $outFile"
            val r = ShellManager.exec(ctx, cmd, 25_000L)
            lastExit = r.exitCode
            r.stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 调用单个原生 getter（排障用）。 */
    @JvmStatic
    fun call(ctx: Context, device: String, method: String): String = runBlocking {
        try {
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪：" + HelperBridge.lastError
            val outFile = "/data/local/tmp/ls-v.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --call $device $method > $outFile 2>&1'; cat $outFile"
            val r = ShellManager.exec(ctx, cmd, 25_000L)
            lastExit = r.exitCode
            lastRaw = r.stdout.trim()
            r.stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 执行探针（1.4.21）：一次跑完三种 app_process 写法，把 exitCode/stdout/stderr 落盘。
     *
     * 目的：不再靠猜 `app_process` 为什么无输出——三种写法结果写进
     * `/sdcard/Android/data/com.byd.ls/files/helper-exec.log`，用 `cat` 远程可读。
     */
    @JvmStatic
    fun probeExecution(ctx: Context): String = runBlocking {
        val dex = HelperBridge.tmpDexPath()
        if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "dex 未就绪：" + HelperBridge.lastError
        val main = "com.byd.ls.helper.HelperMain --list statistic"
        val variants = listOf(
            "A|CLASSPATH=$dex app_process /system/bin $main",
            "B|app_process -Djava.class.path=$dex /system/bin $main",
            "C|app_process /system/bin -Djava.class.path=$dex $main",
            "D|env CLASSPATH=$dex app_process /system/bin $main",
            "E|env CLASSPATH=$dex app_process /system/bin --nice-name=ls-helper $main",
            "F|CLASSPATH=$dex app_process /system/bin --nice-name=ls-helper $main",
            "G|env CLASSPATH=$dex app_process /system/bin --parent-dir /data/local/tmp --nice-name=ls-helper $main"
        )
        val sb = StringBuilder()
        val ctrl = runCatching { ShellManager.exec(ctx, "echo control-ok 2>&1", 8_000L) }.getOrNull()
        sb.append("=== CTRL(echo) ===\nexit: ").append(ctrl?.exitCode ?: -999)
          .append("  out: ").append(ctrl?.stdout?.trim() ?: "(null)").append("\n\n")
        for (v in variants) {
            val tag = v.substringBefore('|')
            val cmd = v.substringAfter('|')
            val r = runCatching { ShellManager.exec(ctx, cmd + " 2>&1", 25_000L) }.getOrNull()
            sb.append("=== ").append(tag).append(" ===\n")
            sb.append("cmd: ").append(cmd).append("\n")
            sb.append("exit: ").append(r?.exitCode ?: -999).append("\n")
            sb.append("out: ").append(r?.stdout?.trim()?.take(600) ?: "(null)").append("\n\n")
        }
        val text = sb.toString()
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            java.io.File(dir, "helper-exec.log").writeText(text)
        }
        lastError = ""
        lastRaw = text.lineSequence().firstOrNull { it.startsWith("out:") }?.take(120) ?: ""
        text
    }

    /** 读 framework 静态字段（触发类初始化，拿到 native 填充的运行时常量）。 */
    @JvmStatic
    fun constField(ctx: Context, cls: String, field: String): String = runBlocking {
        try {
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪"
            val outFile = "/data/local/tmp/ls-v.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --const $cls $field > $outFile 2>&1'; cat $outFile"
            ShellManager.exec(ctx, cmd, 25_000L).stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 直接 binder 调 autoservice txn 9（getIntArray），绕过 Java 层权限检查。 */
    @JvmStatic
    fun binderRead(ctx: Context, deviceId: Int, cmds: String): String = runBlocking {
        try {
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪"
            // 同进程：enableDevice(txn2, callback) → getIntArray(txn9)
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪"
            run {
                val outFile = "/data/local/tmp/ls-e.txt"
                val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                    "com.byd.ls.helper.HelperMain --enablecb $deviceId $cmds > $outFile 2>&1'; cat $outFile"
                val r = ShellManager.exec(ctx, cmd, 30_000L).stdout.trim()
                if (r.isNotEmpty()) return@runBlocking r
            }
            val viaSocket = HelperBridge.socketQuery(ctx, "binder9 $deviceId $cmds")
            if (!viaSocket.isNullOrBlank()) return@runBlocking viaSocket
            val outFile = "/data/local/tmp/ls-b.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --binder9 $deviceId $cmds > $outFile 2>&1'; cat $outFile"
            ShellManager.exec(ctx, cmd, 25_000L).stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 导出某类的全部 static 常量（运行时值），用于一次性拿齐 event id。 */
    @JvmStatic
    fun dumpStatics(ctx: Context, cls: String): String = runBlocking {
        try {
            val viaSocket = HelperBridge.socketQuery(ctx, "statics $cls")
            if (!viaSocket.isNullOrBlank()) return@runBlocking viaSocket
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪"
            val outFile = "/data/local/tmp/ls-s.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --dumpstatics $cls > $outFile 2>&1'; cat $outFile"
            ShellManager.exec(ctx, cmd, 25_000L).stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 注册设备监听并取推送值（迪友同路径），结果落文件后回读。 */
    @JvmStatic
    fun listen(ctx: Context, device: String, seconds: Int): String = runBlocking {
        try {
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪"
            val outFile = "/data/local/tmp/ls-l.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --listen $device $seconds > $outFile 2>&1'; cat $outFile"
            ShellManager.exec(ctx, cmd, (seconds + 20) * 1000L).stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 继承 listener 取具名回调值（油耗/电耗/里程/车速/电量），与迪友同路径。 */
    @JvmStatic
    fun subListen(ctx: Context, device: String, seconds: Int): String = runBlocking {
        try {
            if (!HelperBridge.ensureDexOnly(ctx)) return@runBlocking "ERR dex 未就绪"
            val outFile = "/data/local/tmp/ls-sub.txt"
            val cmd = "sh -c 'env CLASSPATH=${HelperBridge.tmpDexPath()} app_process /system/bin " +
                "com.byd.ls.helper.HelperMain --sublisten $device $seconds > $outFile 2>&1'; cat $outFile"
            ShellManager.exec(ctx, cmd, (seconds + 25) * 1000L).stdout.trim()
        } catch (t: Throwable) {
            "ERR " + (t.message ?: t.javaClass.simpleName)
        }
    }

    fun statusText(): String = when {
        lastError.isNotEmpty() -> "失败(exit=$lastExit)：" + lastError.take(120)
        lastRaw.isNotEmpty() -> lastRaw.take(120)
        lastError.isNotEmpty() -> "失败：" + lastError.take(80)
        else -> "未读取"
    }
}
