package com.byd.ls.shell

import android.content.Context
import android.provider.Settings
import com.byd.ls.util.LsLog

/**
 * ADB 端口开机自愈（1.4.119，做法照 BYDMate 的 AdbRestoreSystem）。
 *
 * BYDMate 的取舍很直接：无线调试端口不是永久资产 —— 重启、设置被改、或 adbd 自己收掉之后，
 * 5555 就关了，它那套 in-app adb 客户端再也连不上。它靠两步救回来：
 *   1. 先用 shell 给自己要 WRITE_SECURE_SETTINGS（我们 1.4.x 的 YBPrivilege 已经在做同一件事）；
 *   2. 用这个权限直接写 `Settings.Global.adb_wifi_enabled = 1`，把无线调试重新打开。
 *
 * 我们以前只 `AdbEndpoint.probe()` 探测不修复：端口一关，特权通道就永久降级到本机 exec，
 * 现场表现是「三指不灵 / screencap 失败 / 一键启动点不动」，只能人工上车开开发者选项。
 *
 * 边界（故意不越）：不碰 `/data/misc/adb/adb_keys`、不重启 adbd、不改 `persist.*` 属性 ——
 * 这些要么需要 root，要么会把同机迪加/迪友的授权一起踢掉。
 */
object AdbNetRestore {

    private const val TAG = "Ling_ADB"

    /** 系统设置键（Settings.Global）：无线调试开关 */
    const val KEY = "adb_wifi_enabled"

    /** 两次自愈的最小间隔：别在通道失败的热路径上反复写系统设置 */
    const val COOLDOWN_MS = 120_000L

    @Volatile private var lastAttemptAt = 0L
    @Volatile var lastVerdict: String = "未尝试"
        private set

    /** 纯判定，单测覆盖：首次必试，之后隔 [cooldownMs] 才试一次。 */
    fun shouldAttempt(nowMs: Long, lastAt: Long, cooldownMs: Long = COOLDOWN_MS): Boolean =
        lastAt <= 0L || nowMs - lastAt >= cooldownMs

    /** 读开关；-1 = 读不到（没权限 / 键不存在） */
    fun readEnabled(ctx: Context): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, KEY, -1)
    }.getOrDefault(-1)

    /** 写开关并读回复核（BYDMate 的 writeAdbWifiEnabled 也是写完立刻读回）。 */
    fun writeEnabled(ctx: Context, value: Int = 1): Boolean = runCatching {
        Settings.Global.putInt(ctx.contentResolver, KEY, value)
        readEnabled(ctx) == value
    }.getOrDefault(false)

    /**
     * 端口关了才动手。返回一句可直接进日志/远程状态页的结论。
     * 调用方不必判断是否要试：[shouldAttempt] 已在内部把关，冷却中直接返回上次结论。
     */
    suspend fun ensure(ctx: Context): String {
        if (AdbEndpoint.probe()) return "端口在（" + AdbEndpoint.DEFAULT_HOST + ":" + AdbEndpoint.DEFAULT_PORT + "），不需要自愈"
        val now = System.currentTimeMillis()
        if (!shouldAttempt(now, lastAttemptAt)) return "冷却中（$lastVerdict）"
        lastAttemptAt = now
        val before = readEnabled(ctx)
        val verdict = if (before == 1) {
            "adb_wifi_enabled 已是 1 但端口未监听：adbd 没起来，需要人工或重启系统"
        } else {
            val ok = writeEnabled(ctx, 1)
            val up = AdbEndpoint.probe()
            when {
                up -> "写 adb_wifi_enabled 由 $before → 1 后端口已监听（ok=$ok）"
                ok -> "写 adb_wifi_enabled=1 成功（原值 $before），端口仍关：adbd 未跟随即刻生效"
                else -> "写 adb_wifi_enabled 被拒（原值 $before）：需要 shell 一次性重新授予 WRITE_SECURE_SETTINGS"
            }
        }
        lastVerdict = verdict
        LsLog.w(TAG, "adb 自愈：$verdict")
        return verdict
    }
}
