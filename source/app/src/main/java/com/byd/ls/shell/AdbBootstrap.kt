package com.byd.ls.shell

import android.content.Context
import com.byd.ls.util.LsLog
import kotlinx.coroutines.delay

/**
 * 一键启动里的 ADB 授权步骤（1.2.22：只有 adblib 一路）。
 *
 * 只有用户点了「一键启动」才会走到这里；开机/后台/预热仍然静默，不发送公钥。
 * adblib 的 connect() 内部走「先签名，被拒再发公钥」的流程，所以这里只需要：
 *   1. 端口在不在（TCP 探测，不碰 adbd，不会弹窗）；
 *   2. 调一次 connect，拿到 true 即授权成功；
 *   3. 失败则给用户 60s 窗口重复尝试（用户在车机屏幕上点「允许」需要时间）。
 */
object AdbBootstrap {

    private const val TAG = "AdbBootstrap"

    /** 用户点「允许」的窗口；adbd 弹窗最长 60s 消失 */
    private const val AUTHORIZATION_WAIT_MS = 60_000L
    private const val RETRY_INTERVAL_MS = 4_000L

    sealed class Event {
        data object NeedPort : Event()
        data object WaitAllow : Event()
        data class Done(val authorized: Boolean, val detail: String) : Event()
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun ensureAuthorized(
        ctx: Context,
        onEvent: (Event) -> Unit
    ): Boolean {
        // 已信任：静默握手成功，不弹窗
        val lib = ShellManager.adbLibChannel()
        if (lib.connect()) {
            onEvent(Event.Done(true, "ADB 已授权"))
            return true
        }
        if (!AdbEndpoint.probe()) {
            onEvent(Event.NeedPort)
            onEvent(Event.Done(false, "未检测到 ADB 端口"))
            return false
        }

        // 端口在、key 未信任：用户已主动点一键启动，允许发一次公钥并等用户点「允许」
        LsLog.i(TAG, "端口在但未授权（${lib.lastDetail}），转入 ${AUTHORIZATION_WAIT_MS}ms 授权等待")
        onEvent(Event.WaitAllow)
        val deadline = System.currentTimeMillis() + AUTHORIZATION_WAIT_MS
        var lastDetail = lib.lastDetail
        while (System.currentTimeMillis() < deadline) {
            delay(RETRY_INTERVAL_MS)
            // 每次重试都会重新走握手：adbd 在用户点「允许」后需要一次新的 CNXN 才能放行
            if (lib.connect(force = true)) {
                onEvent(Event.Done(true, "已授权"))
                return true
            }
            lastDetail = lib.lastDetail
            if (!AdbEndpoint.probe()) break
        }
        onEvent(Event.Done(false, lastDetail))
        return false
    }
}
