package com.byd.ls.remote

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 车机侧审计环：谁在什么时候做过什么。64KB 环形，现场可直接读。 */
object RemoteAudit {

    private const val MAX = 64 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun file(ctx: Context) = File(ctx.filesDir, "remote_audit.log")

    fun log(ctx: Context, msg: String) {
        runCatching {
            val f = file(ctx)
            if (f.length() > MAX) {
                val keep = f.readText().takeLast(MAX / 2)
                f.writeText(keep)
            }
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
        }
    }

    fun tail(ctx: Context, lines: Int): String = runCatching {
        file(ctx).readLines().takeLast(lines.coerceIn(1, 500)).joinToString("\n")
    }.getOrDefault("")
}
