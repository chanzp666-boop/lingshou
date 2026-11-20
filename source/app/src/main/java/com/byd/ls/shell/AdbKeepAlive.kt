package com.byd.ls.shell

import com.byd.ls.util.LsLog

/**
 * adblib 通道的连接生命周期（1.2.21 新增）。
 *
 * 设计依据是「用完即断」（ADB自授权要点-给灵手.md 第 5 节，仪表助理 v4.5 真车结论）：
 * 车机是多客户端抢 adbd 的环境（迪加 / 迪友 各有一条连接），
 * 本 ROM 的 adbd 在「待授权」状态不回包，一条常驻连接会把待授权槽位长期占死，
 * 别人再也授权不上。所以：
 *
 *  - **不保持常驻连接**：一条命令一条连接，执行完就让 [AdbLibChannel] 关闭；
 *  - **空闲兜底**：万一有连接漏关，reaper 在 [IDLE_MS] 后强行断开；
 *  - **退避只在失败时增长，成功即归零**：否则顺序命令会被自己的退避卡死；
 *  - **探活按需**：到 [MIN_INTERVAL_MS] 门限才发一次 echo，未到直接返回上次结果。
 *
 * 代价：一次握手约 100ms（adblib 在库内跑完 CNXN/AUTH）；
 * 收益：adbd 上常态零连接，同机的其它客户端随时抢得到授权。
 */
object AdbKeepAlive {

    private const val TAG = "Ling_ADB"

    /** 两次探活的最小间隔：比它更近直接跳过，避免手势高频触发狂发 echo */
    const val MIN_INTERVAL_MS = 14_000L

    /** 单轮最多重建几次（跨轮不累计，每轮重新计数） */
    const val MAX_REBUILD_PER_ROUND = 3

    /** 空闲多久强制断开（reaper 兜底；正常情况下命令结束就关了） */
    /**
     * 空闲回收阈值。
     *
     * 1.4.8 起连接改为复用（不再每条命令断连，见 AdbLibChannel.exec）：
     * 空闲超过这个时间就断开，既省握手、又不长期占 adbd 的待授权槽位。
     * 15s 取值依据：卡片采样周期 2s（7 个周期）、心跳 60s（远大于本值）。
     */
    const val IDLE_MS = 15_000L

    /** 退避上限 */
    const val MAX_BACKOFF_MS = 60_000L

    @Volatile private var lastProbeAt = 0L
    @Volatile private var lastUseAt = 0L
    @Volatile private var lastResult = true
    @Volatile var backoffMs = 0L
        private set
    @Volatile private var lastTryAt = 0L

    /** 上次探活结果，供状态页展示 */
    val healthy: Boolean get() = lastResult

    /** 到点就探活一次；还没到探活时间返回 true（不阻塞手势） */
    fun due(now: Long = System.currentTimeMillis()): Boolean =
        now - lastProbeAt >= MIN_INTERVAL_MS

    /** 记一次「刚用过」，reaper 据此判断空闲 */
    fun touch(now: Long = System.currentTimeMillis()) {
        lastUseAt = now
    }

    fun idleSince(now: Long = System.currentTimeMillis()): Long =
        if (lastUseAt == 0L) 0L else now - lastUseAt

    /**
     * 按需连接入口：带退避，绝不连环重连。
     *
     * @return true 表示现在可以建连；false 表示还在退避窗口里，调用方应直接返回失败。
     */
    fun mayConnect(now: Long = System.currentTimeMillis()): Boolean =
        backoffMs <= 0L || now - lastTryAt >= backoffMs

    /** 连接成功：退避归零（顺序命令不被自己的退避卡死） */
    fun onConnected() {
        backoffMs = 0L
        lastTryAt = System.currentTimeMillis()
        lastProbeAt = lastTryAt
        lastUseAt = lastTryAt
        lastResult = true
    }

    /** 连接失败：退避 5s 起、翻倍、上限 60s */
    fun onFailed() {
        val now = System.currentTimeMillis()
        backoffMs = minOf(if (backoffMs <= 0L) 5_000L else backoffMs * 2, MAX_BACKOFF_MS)
        lastTryAt = now
        lastResult = false
        LsLog.w(TAG, "adblib 连接失败，退避 " + backoffMs + "ms（成功即归零）")
    }

    /**
     * 探活 + 失败重建。返回通道最终是否可用。
     *
     * @param channel 目标通道
     * @param rebuild 重建动作（通常传 channel::connect）
     */
    suspend fun probeAndHeal(
        channel: AdbLibChannel,
        rebuild: suspend () -> Boolean
    ): Boolean {
        if (!due()) return channel.isReady()
        lastProbeAt = System.currentTimeMillis()

        if (channel.probe()) {
            lastResult = true
            touch()
            return true
        }

        LsLog.w(TAG, "adblib 探活失败，开始重建（最多 " + MAX_REBUILD_PER_ROUND + " 次）")
        var attempt = 0
        while (attempt < MAX_REBUILD_PER_ROUND) {
            attempt++
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
            if (rebuild() && channel.probe()) {
                LsLog.i(TAG, "adblib 第 " + attempt + " 次重建成功")
                onConnected()
                return true
            }
            onFailed()
        }
        lastResult = false
        return false
    }

    /** 手动标记（连接成功/关闭时同步，避免刚连上就因为陈旧结果被判死） */
    fun mark(ok: Boolean) {
        lastResult = ok
        lastProbeAt = System.currentTimeMillis()
        if (ok) onConnected() else onFailed()
    }

    /**
     * 用户在授权流程里主动重试：**清掉退避窗口**，让 [connect] 立刻能再试一次。
     *
     * 不清会让「点了允许」被自己的 5s/10s/20s 退避挡在门外，现场表现就是
     * 「点了允许还是过不去」。这里只清时间窗，不改 lastResult（探活结果照旧）。
     */
    fun resetBackoffForRetry() {
        backoffMs = 0L
        lastTryAt = 0L
    }

    /** 单测用：把退避与时间戳清回初始态 */
    fun resetForTest() {
        lastProbeAt = 0L
        lastUseAt = 0L
        lastResult = true
        backoffMs = 0L
        lastTryAt = 0L
    }

    private const val RETRY_DELAY_MS = 2_000L
}
