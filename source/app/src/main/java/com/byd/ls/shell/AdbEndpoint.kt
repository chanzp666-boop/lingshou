package com.byd.ls.shell

import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADB 端点常量（1.2.22 起唯一一路：内置 adblib）。
 *
 * 1.2.21 及以前自研 AdbTcpChannel 也持有 host/port 常量，该通道已按要求整体删除，
 * 默认值搬到这里，避免调用方散落写死 "127.0.0.1" / 5555。
 */
/**
 * ADB 授权状态（原定义在已删除的 AdbTcpChannel 里，1.2.22 搬到这里）。
 *
 * 只保留界面/流程真正会区分的那几个，避免"状态枚举比逻辑还长"。
 */
enum class AdbAuthState {
    /** 已握手成功，可用 */
    AUTHORIZED,

    /** 端口在，等用户在车机屏幕上点「允许」 */
    WAITING_USER,

    /** 端口在，但密钥未被信任且没有弹窗可点 */
    UNAUTHORIZED,

    /** 端口未监听 */
    PORT_CLOSED,

    /** TCP 连上但握手没完成 */
    CONNECT_TIMEOUT,

    /** TCP 已建连，握手进行中 */
    TCP_CONNECTED
}

object AdbEndpoint {

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 5555

    /** 只做 TCP 连通性探测（不握手、不弹授权框）；默认 1.2s 超时，开机路径里也不拖时间 */
    fun probe(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT, timeoutMs: Int = 1_200): Boolean =
        runCatching {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
}
