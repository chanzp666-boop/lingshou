package com.byd.ls.shell

import com.byd.ls.util.LsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 兜底通道：以 App 自身 UID 执行命令。
 *
 * ⚠️ 无特权。Android 10 上 `input keyevent` 需要 INJECT_EVENTS 权限，以 App UID 运行会直接
 * SecurityException；`settings put secure` 需要 WRITE_SECURE_SETTINGS，同样会失败。
 * 因此这条通道只用于无需特权的查询命令，手势注入必须走内置 ADB 通道。
 */
class LocalChannel : ShellChannel {

    override val id = "local"
    override val displayName = "本机（无特权，仅兜底）"
    override fun isReady() = true
    override fun isPrivileged() = false

    override suspend fun connect(): Boolean = true

    override suspend fun exec(cmd: String, timeoutMs: Long, bestEffort: Boolean): ShellOutput = withContext(Dispatchers.IO) {
        val r = withTimeoutOrNull(timeoutMs) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroy()
                    ShellOutput(out + "\n[timeout]", -1)
                } else {
                    ShellOutput(out + if (err.isNotBlank()) "\n$err" else "", p.exitValue())
                }
            } catch (t: Throwable) {
                LsLog.w(TAG, "local exec 失败: ${t.message}")
                ShellOutput(t.message ?: "unknown error", -1)
            }
        }
        r ?: ShellOutput("[timeout after ${timeoutMs}ms]", -1)
    }

    override fun close() {}

    private companion object { const val TAG = "LocalChannel" }
}
