package com.byd.ls.shell

/**
 * shell 通道的统一抽象。
 *
 * 两条实现按优先级排列：
 *  1. [AdbLibChannel] —— 唯一特权通道。内置 adblib 连 127.0.0.1:5555，
 *     adbd 以 shell(UID 2000) 身份 fork 进程，天然有 input/settings 权限。
 *     前置条件：车机 adbd 处于 tcpip 模式（127.0.0.1:5555）；客户端内置车机已信任的固定密钥，
 *     本地回环不弹授权框，无需复制 / 注入公钥。
 *  2. [LocalChannel]   —— 兜底。Runtime.exec 以 App 自身 UID 运行，**没有**注入按键/改 secure settings 的能力，
 *     只能跑无需特权的查询命令（例如读 logcat、检测文件是否存在）。
 */
data class ShellOutput(val stdout: String, val exitCode: Int) {
    val success: Boolean get() = exitCode == 0
    val trimmed: String get() = stdout.trim()
}

interface ShellChannel {
    /** 通道唯一标识，写进日志用 */
    val id: String
    /** 界面上给用户看的名字 */
    val displayName: String

    /** 是否已连接且可用（不触发 IO 的快速判断） */
    fun isReady(): Boolean

    /** 是否需要 shell(UID 2000) 及以上特权。false 时不能执行 input keyevent / settings put secure */
    fun isPrivileged(): Boolean

    /** 建立/恢复连接。失败返回 false，不抛异常 */
    suspend fun connect(): Boolean

    /**
     * 执行一条命令，返回合并输出与退出码。
     *
     * [bestEffort] = true：这条命令慢是常态（例如 dash 屏 screencap），失败/超时只影响本次返回，
     * 不把整条通道判坏 —— 不计 failStreak、不触发 5s 重探闸门。
     * 1.4.120 实车：nav_probe 每 2~3s 一次 screencap，6s 硬超时一次就把全局通道按成
     * 「降级中」，fid 轮询/看门狗/装包全被连坐（70s 内 129 次通道失败）。
     */
    suspend fun exec(cmd: String, timeoutMs: Long = 8_000L, bestEffort: Boolean = false): ShellOutput

    /** 释放资源 */
    fun close()
}
