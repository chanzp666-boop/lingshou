package com.byd.ls.shell

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.byd.ls.config.GesturePrefs
import com.byd.ls.service.A11yHealer
import com.byd.ls.util.LsLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无人值守的 ADB 授权。
 *
 * 车机上没有电脑可用时，app 自己往 127.0.0.1:5555 发一次公钥，让 adbd 主动弹出
 * 「允许 USB 调试吗」；用户在车机屏幕上点一次「允许」后，收尾（重选通道、补无障碍）全自动。
 *
 * 现场最怕的不是"没弹"，而是"一直在弹"，所以闸门是分层的：
 *  · 手势总开关关着 → 一次都不试（用户明确说过不要）；
 *  · 通道已经特权 → 只做一次内存判断就返回，连探测都不做，更不会弹窗；
 *  · 端口没开（PORT_CLOSED）→ 公钥根本没发出去，不消耗额度；
 *  · 用户主动打开 App（reason=open 且 force）→ 随叫随到，不占额度也不推后间隔；
 *  · 其余无人值守路径 → 每次开机最多 [MAX_PER_BOOT] 次、两次间隔 ≥ [MIN_INTERVAL_MS]。
 *
 * 额度记在 SharedPreferences 而不是内存字段：进程被"应用加速管理"杀掉后重新拉起，
 * 也不该凭空多出弹窗额度。
 *
 * 计数用两步法（防"等待中被杀 ⇒ 白得额度"）：
 *  1) 发公钥前先写 pending 标记；
 *  2) 拿到结果后按终态结算：端口开着的结论才转成正式计数并清 pending，PORT_CLOSED 只清 pending。
 *  进程若在两步之间被杀，下次进入看到 pending 就保守补记一次——宁可少弹，不可多弹。
 */
object AdbAutoAuth {

    private const val TAG = "AutoAuth"

    /** 独立 SP 文件：与 ls_gesture_prefs 分开，避免用户改设置时连带清掉节流记录 */
    private const val SP_FILE = "ls_autoauth"
    private const val KEY_LAST_AT = "ls_autoauth_last_at"
    private const val KEY_BOOT_COUNT = "ls_autoauth_boot_count"

    /** 非 0 表示"公钥可能已发出、结果还没结算"，值本身是写入时刻的 elapsedRealtime */
    private const val KEY_PENDING_AT = "ls_autoauth_pending_at"

    /** 无人值守路径每次开机最多 3 次（开机 1 + 心跳 2）；open 那一路不占这个额度 */
    private const val MAX_PER_BOOT = 3

    /** 无人值守路径的最小间隔：10 分钟，让用户有时间去车机屏幕上点「允许」 */
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** 用户主动打开 App 的触发来源：这一路随叫随到，不占额度 */
    private const val REASON_OPEN = "open"

    /**
     * 总开关：自动触发保持关闭。
     *
     * DiLink 4.0 上 boot/heartbeat/open 多路自动发起会形成弹窗风暴；现在首次授权
     * 由用户点「一键启动」走 [AdbBootstrap] 的 connectForAuthorization() 弹一次，
     * 自动路径不恢复，避免重复弹窗。
     */
    private const val AUTO_AUTH_ENABLED = false

    /** 同一次授权最长等 60s，期间不允许第二条连接进来抢 authConn，否则会互相顶掉弹窗 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 若特权通道不可用，就尝试发起一次 ADB 授权。
     *
     * @param reason 触发来源（boot / heartbeat / open），只用于日志定位
     * @param force 只跳过 [MIN_INTERVAL_MS] 间隔限制；reason=open 且 force 的那一路额外不消耗额度
     * @return true 表示本次调用结束时确实拿到了特权通道
     */
    suspend fun maybeRequestAuthorization(
        ctx: Context,
        reason: String,
        force: Boolean = false
    ): Boolean {
        val app = ctx.applicationContext
        // 调用方可能是广播接收器或 onResume 主线程，所以整个流程先切到 IO
        return withContext(Dispatchers.IO) {
            try {
                if (!AUTO_AUTH_ENABLED) {
                    LsLog.i(TAG, "reason=$reason skip=disabled")
                    return@withContext false
                }
                GesturePrefs.init(app)
                if (!GesturePrefs.enabled) {
                    LsLog.i(TAG, "reason=$reason skip=gesture-off")
                    return@withContext false
                }
                // 缓存通道已是特权：说明密钥早被信任，这里再弹窗就是纯打扰
                if (ShellManager.isPrivileged()) {
                    LsLog.d(TAG, "reason=$reason skip=privileged")
                    return@withContext false
                }
                if (!inFlight.compareAndSet(false, true)) {
                    LsLog.i(TAG, "reason=$reason skip=inflight")
                    return@withContext false
                }
                try {
                    attempt(app, reason, force)
                } finally {
                    inFlight.set(false)
                }
            } catch (t: Throwable) {
                // 开机、心跳、手势三条链路都不能被授权逻辑带崩：这里只记日志
                LsLog.w(TAG, "reason=$reason skip=exception error=${t.message}")
                false
            }
        }
    }

    /** 节流判定 + 静默探测 + 发公钥 + 结算额度，串行执行（inFlight 已保证同时只有一路） */
    private suspend fun attempt(ctx: Context, reason: String, force: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val sp = ctx.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
        // 用户主动打开 App 这一路不占额度，也不写 last_at（否则会把心跳的间隔往后推）
        val openCall = force && reason == REASON_OPEN

        val ledger = resolveLedger(sp, now, reason)
        var used = ledger.used

        if (!openCall) {
            if (used >= MAX_PER_BOOT) {
                LsLog.i(TAG, "reason=$reason skip=quota state=none consumed=false bootLeft=0")
                return false
            }
            if (!force && ledger.lastAt > 0L && now >= ledger.lastAt &&
                now - ledger.lastAt < MIN_INTERVAL_MS
            ) {
                // 用 elapsedRealtime 而不是墙钟：用户改系统时间也骗不过这个间隔
                val leftSec = (MIN_INTERVAL_MS - (now - ledger.lastAt)) / 1000L
                LsLog.i(
                    TAG,
                    "reason=$reason skip=interval state=none consumed=false " +
                        "bootLeft=${bootLeftOf(used)} waitSec=$leftSec"
                )
                return false
            }
        }

        // 静默探测（connect 只做签名、不发公钥）：通道其实好着就绝不弹窗
        val before = ShellManager.reprobe(ctx)
        if (before.isPrivileged()) {
            LsLog.i(
                TAG,
                "reason=$reason skip=privileged-after-probe channel=${before.id} " +
                    "consumed=false bootLeft=${bootLeftOf(used)}"
            )
            return false
        }

        val state0 = ShellManager.adbAuthState()
        // 端口没开 ⇒ 公钥根本发不出去，这次不算额度：用户随后打开 USB 调试还能被自动补上
        if (state0 == AdbAuthState.PORT_CLOSED) {
            LsLog.w(
                TAG,
                "reason=$reason state=PORT_CLOSED skip=port-closed consumed=false " +
                    "bootLeft=${bootLeftOf(used)}"
            )
            return false
        }

        // 只有可能消耗额度的路径才写 pending：open 不占额度，用不着这层兜底
        if (!openCall) sp.edit().putLong(KEY_PENDING_AT, now).commit()

        LsLog.i(
            TAG,
            "发起自动授权: reason=$reason channel=${before.id} state=$state0 " +
                "force=$force openCall=$openCall bootLeft=${bootLeftOf(used)}"
        )
        // ensureAuthorized 内部已按新状态分流：只有 PORT_CLOSED 才提示端口，其余都等用户点允许
        val ok = AdbBootstrap.ensureAuthorized(ctx) { ev -> LsLog.i(TAG, "授权事件($reason): $ev") }

        // 结算：只有"端口确实通着"的终态才消耗额度；PORT_CLOSED 说明这次没发出公钥
        val endState = ShellManager.adbAuthState()
        val consumed = !openCall && endState != AdbAuthState.PORT_CLOSED
        if (consumed) {
            used += 1
            sp.edit()
                .putInt(KEY_BOOT_COUNT, used)
                .putLong(KEY_LAST_AT, now)
                .putLong(KEY_PENDING_AT, 0L)
                .commit()
        } else if (!openCall) {
            sp.edit().putLong(KEY_PENDING_AT, 0L).commit()
        }
        LsLog.i(
            TAG,
            "reason=$reason state=$endState consumed=$consumed " +
                "bootLeft=${bootLeftOf(used)} ok=$ok openCall=$openCall"
        )
        if (!ok) return false

        // 授权成功后重选通道：adblib 可用就用它，比每次 fork 一个 app_process 快得多
        val after = ShellManager.reprobe(ctx)
        if (!after.isPrivileged()) {
            LsLog.w(TAG, "授权返回成功但重探仍无特权通道: reason=$reason channel=${after.id}")
            return false
        }
        // 顺手补一次无障碍：用户刚在车机上点过「允许」，这是整条链路里唯一需要人手的环节
        val repair = runCatching { A11yHealer.repairNow(ctx) }
            .getOrElse { "无障碍修复异常: ${it.message}" }
        LsLog.i(TAG, "授权后自愈($reason): $repair")
        return true
    }

    private fun bootLeftOf(used: Int): Int = (MAX_PER_BOOT - used).coerceAtLeast(0)

    /** 本次开机的已用次数与上次尝试时刻 */
    private data class Ledger(val used: Int, val lastAt: Long)

    /**
     * 读取并结算额度。
     *
     * elapsedRealtime 开机归零：存值比当前大 ⇒ 车机重启过，本次开机从 0 重新算。
     * pending 是上一次"公钥已发、结果未落账"的现场：同一开机内一律保守补记一次，
     * 跨开机的 pending 直接作废（那次弹窗属于上一次开机，与本次额度无关）。
     */
    private fun resolveLedger(sp: SharedPreferences, now: Long, reason: String): Ledger {
        var used = sp.getInt(KEY_BOOT_COUNT, 0)
        var lastAt = sp.getLong(KEY_LAST_AT, 0L)
        if (lastAt > now) {
            used = 0
            lastAt = 0L
        }

        val pendingAt = sp.getLong(KEY_PENDING_AT, 0L)
        if (pendingAt > 0L) {
            if (pendingAt <= now) {
                // 上次进程在等弹窗时被杀（公钥可能已经发出去了），补记一次再清标记
                used = minOf(used + 1, MAX_PER_BOOT)
                lastAt = maxOf(lastAt, pendingAt)
                sp.edit()
                    .putInt(KEY_BOOT_COUNT, used)
                    .putLong(KEY_LAST_AT, lastAt)
                    .putLong(KEY_PENDING_AT, 0L)
                    .commit()
                LsLog.w(
                    TAG,
                    "发现上次授权未结算(pending)，保守补记一次: reason=$reason " +
                        "consumed=true bootLeft=${bootLeftOf(used)}"
                )
            } else {
                LsLog.i(TAG, "pending 属于上一次开机，作废: reason=$reason")
                sp.edit().putLong(KEY_PENDING_AT, 0L).commit()
            }
        }
        return Ledger(minOf(used, MAX_PER_BOOT), lastAt)
    }
}
