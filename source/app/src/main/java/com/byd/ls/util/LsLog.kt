package com.byd.ls.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 车机现场调试用的极简日志器。
 *
 * 1.2.22 起默认**全关**：现场反馈日志拖 CPU（每条都要格式化时间戳 + 落盘一行），
 * 而稳态下 60s 巡检基本不产生有用信息。开关在 GesturePrefs.logEnabled，
 * 界面「日志」卡片里手动打开才会写 logcat 与 filesDir/ls.log。
 *
 * 关掉时入口最前面就 return，不排队、不碰磁盘、不做时间戳格式化。
 * 例外：ERROR 级与 [crash] 永远落盘 —— 那是排查"开机没起来 / 为什么掉无障碍"的唯一线索。
 */
object LsLog {

    private const val GLOBAL_TAG = "LingShou"
    private const val MAX_BYTES = 256 * 1024L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ls-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null

    /** 日志总开关：默认关。由 GesturePrefs 同步过来，界面改动后立即生效。 */
    @Volatile var enabled: Boolean = false

    fun init(ctx: Context) {
        if (logFile == null) {
            // 外部目录优先：release 包非 debuggable，run-as 读不到 filesDir，
            // 现场排障只能靠 /sdcard/Android/data/com.byd.ls/files/ls.log
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            logFile = File(dir, "ls.log")
        }
    }

    fun i(tag: String, msg: String) { if (enabled) write(Log.INFO, tag, msg, null) }
    fun w(tag: String, msg: String) { if (enabled) write(Log.WARN, tag, msg, null) }
    fun d(tag: String, msg: String) { if (enabled) write(Log.DEBUG, tag, msg, null) }

    /** 错误：无论开关都落盘，保证"为什么没恢复"可查 */
    fun e(tag: String, msg: String, t: Throwable? = null) = write(Log.ERROR, tag, msg, t)

    /** 崩溃专用：无视开关，永远落盘（LsApp 的默认异常处理器用） */
    fun crash(tag: String, msg: String, t: Throwable? = null) = write(Log.ERROR, tag, msg, t)

    private fun write(level: Int, tag: String, msg: String, t: Throwable?) {
        if (!enabled && level != Log.ERROR) return
        when (level) {
            Log.INFO -> Log.i(GLOBAL_TAG, "[$tag] $msg")
            Log.WARN -> Log.w(GLOBAL_TAG, "[$tag] $msg")
            Log.DEBUG -> Log.d(GLOBAL_TAG, "[$tag] $msg")
            else -> Log.e(GLOBAL_TAG, "[$tag] $msg", t)
        }
        val f = logFile ?: return
        val lvl = when (level) { Log.INFO -> "I"; Log.WARN -> "W"; Log.DEBUG -> "D"; else -> "E" }
        val suffix = t?.let { "\n  " + Log.getStackTraceString(it) } ?: ""
        // 时间戳格式化放到单线程 IO 任务里，SimpleDateFormat 不做并发保护，避免多线程调用竞态
        io.execute {
            try {
                val stamp = fmt.format(Date())
                val line = "$stamp $lvl/$tag: $msg$suffix\n"
                if (f.length() > MAX_BYTES) {
                    val bytes = f.readBytes()
                    val keep = bytes.copyOfRange((bytes.size / 2).toInt(), bytes.size)
                    f.writeBytes(keep)
                }
                f.appendText(line)
            } catch (_: Throwable) { }
        }
    }

    /** 清空日志文件（界面「清空」按钮用） */
    fun clear() { runCatching { logFile?.writeText("") } }

    /** 供界面读取最近日志，方便在车机上直接看错误 */
    fun tail(maxChars: Int = 4000): String {
        val f = logFile ?: return ""
        return try {
            f.readText().let { if (it.length > maxChars) it.takeLast(maxChars) else it }
        } catch (_: Throwable) { "" }
    }
}
