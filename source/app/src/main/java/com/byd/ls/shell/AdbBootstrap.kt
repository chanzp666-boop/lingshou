package com.byd.ls.shell

import android.content.Context
import com.byd.ls.util.LsLog

/**
 * 一键启动里的 ADB 授权步骤（1.2.22：只有 adblib 一路）。
 *
 * 只有用户点了「一键启动」才会走到这里；开机/后台/预热仍然静默，不发送公钥。
 * adblib 默认 abortOnUnauthorised=true，第二次 AUTH 不会发公钥；手动授权必须走
 * [AdbLibChannel.connectForAuthorization]，否则车机永远不会弹「允许」。
 */
object AdbBootstrap {

    private const val TAG = "AdbBootstrap"

    /** 用户点「允许」的窗口；adbd 弹窗最长 60s 消失 */
    private const val AUTHORIZATION_WAIT_MS = 60_000L

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

        // 端口在、key 未信任：用户已主动点一键启动，发一次公钥并等用户点「允许」
        LsLog.i(TAG, "端口在但未授权（${lib.lastDetail}），转入 ${AUTHORIZATION_WAIT_MS}ms 授权等待")
        onEvent(Event.WaitAllow)
        val authorized = lib.connectForAuthorization(AUTHORIZATION_WAIT_MS)
        if (authorized) {
            onEvent(Event.Done(true, "已授权"))
            return true
        }
        onEvent(Event.Done(false, lib.lastDetail))
        return false
    }
}
