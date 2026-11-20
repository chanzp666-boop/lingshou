package com.byd.ls.shell

import android.content.Context
import android.util.Base64
import com.byd.ls.config.GesturePrefs
import com.byd.ls.util.LsLog
import java.security.MessageDigest
import kotlinx.coroutines.delay

/**
 * BYD 诊断日志开关（tag 级别，运行时 + persist 双写）。
 *
 * 旧方案 `pm disable-user com.byd.logswitch` 实测无效：logd 仍被 BYD SDK 的
 * CAN 事件 tag 打满。现方案由 `/data/local/tmp/byd-log-toggle.sh` 把
 * BYDAutoYunDevice 等 9 个 tag 的 `log.tag.*` / `persist.log.tag.*` 设为 S，
 * 恢复时置空。App 只负责确保脚本存在、调用脚本、按 stdout 回填。
 */
object BydLogSwitch {

    private const val TAG = "BydLogSwitch"
    private const val SCRIPT = "/data/local/tmp/byd-log-toggle.sh"
    private const val SCRIPT_ASSET = "byd-log-toggle.sh"
    private const val BOOT_GAP_MS = 10 * 60 * 1000L

    enum class State { ENABLED, DISABLED, UNKNOWN }

    @Volatile private var lastBootApplyAt = 0L

    /** 真实状态：脚本 stdout `state=off` = 已压制；`state=on` = 正常。 */
    suspend fun status(ctx: Context): State {
        if (!ensureScript(ctx)) return State.UNKNOWN
        val script = run(ctx, "sh $SCRIPT status", 6_000L) ?: return State.UNKNOWN
        return parseScript(script.stdout) ?: State.UNKNOWN
    }

    /**
     * 幂等设置；最多尝试 [attempts] 次（默认 2）。
     * 返回 true 表示最终已处于目标状态。脚本不可用时直接失败，不回退 pm。
     */
    suspend fun setDisabled(ctx: Context, disabled: Boolean, attempts: Int = 2): Boolean {
        if (!ensureScript(ctx)) return false
        val target = if (disabled) State.DISABLED else State.ENABLED
        repeat(attempts.coerceAtLeast(1)) {
            if (status(ctx) == target) return true
            val cmd = if (disabled) "sh $SCRIPT off" else "sh $SCRIPT on"
            val r = run(ctx, cmd, 10_000L)
            if (parseScript(r?.stdout) == target || status(ctx) == target) return true
            delay(500L)
        }
        LsLog.w(TAG, "设置失败 target=" + target)
        return false
    }

    /** 开机 ADB 连上后调用：只有用户把开关打开过才重放 off。 */
    suspend fun reapplyIfNeeded(ctx: Context): Boolean {
        if (!GesturePrefs.bydLogDisabled) return true
        val now = System.currentTimeMillis()
        if (now - lastBootApplyAt < BOOT_GAP_MS) return true
        lastBootApplyAt = now
        return setDisabled(ctx, true, 2)
    }

    /** 脚本必须由 App 自己保证存在/内容正确；换车或清 /data/local/tmp 后仍能恢复。 */
    private suspend fun ensureScript(ctx: Context): Boolean {
        val bytes = runCatching {
            ctx.assets.open(SCRIPT_ASSET).use { it.readBytes() }
        }.getOrNull() ?: return false
        val want = sha256(bytes)
        val cur = run(ctx, "sha256sum $SCRIPT 2>/dev/null", 4_000L)
            ?.stdout?.trim()?.substringBefore(' ').orEmpty()
        if (cur == want) return true
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val cmd = "printf %s " + b64 +
            " | base64 -d > $SCRIPT.tmp && chmod 755 $SCRIPT.tmp && mv $SCRIPT.tmp $SCRIPT"
        val r = run(ctx, cmd, 8_000L)
        return r?.success == true
    }

    private suspend fun run(ctx: Context, cmd: String, timeoutMs: Long): ShellOutput? =
        runCatching { ShellManager.exec(ctx, cmd, timeoutMs) }.getOrNull()

    private fun parseScript(out: String?): State? {
        val s = out?.lowercase() ?: return null
        return when {
            s.contains("state=off") || s.contains("state=disabled") -> State.DISABLED
            s.contains("state=on") || s.contains("state=enabled") -> State.ENABLED
            else -> null
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }
}
