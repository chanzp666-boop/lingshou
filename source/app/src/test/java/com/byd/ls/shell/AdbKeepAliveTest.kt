package com.byd.ls.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接生命周期回归（1.2.21）。
 *
 * 这些参数是「用完即断 + 失败退避」策略的全部数值，改错了的症状很具体：
 *  - 退避不归零 → 顺序命令被自己的退避卡死；
 *  - 不设空闲门限 → 连接长期挂在 adbd 上，同机其它客户端授权不上；
 *  - 探活门限太短 → 每次手势都白等一次 echo。
 */
class AdbKeepAliveTest {

    @After
    fun reset() {
        AdbKeepAlive.resetForTest()
    }

    @Test
    fun `lifecycle constants match the field-tested rhythm`() {
        assertEquals(14_000L, AdbKeepAlive.MIN_INTERVAL_MS)
        assertEquals(3, AdbKeepAlive.MAX_REBUILD_PER_ROUND)
        // 1.4.8：连接复用（不再每命令断连）后空闲回收压到 15s，见 docs/03
        assertEquals(15_000L, AdbKeepAlive.IDLE_MS)
        assertEquals(60_000L, AdbKeepAlive.MAX_BACKOFF_MS)
    }

    @Test
    fun `probe is throttled right after a successful connect`() {
        AdbKeepAlive.onConnected()
        assertFalse("刚连上不应立刻再探活", AdbKeepAlive.due(System.currentTimeMillis()))
        assertTrue("过了门限就应重新探活", AdbKeepAlive.due(System.currentTimeMillis() + 14_001L))
    }

    @Test
    fun `backoff doubles on failures and resets on success`() {
        AdbKeepAlive.resetForTest()
        assertTrue("初始没有退避，随时可以连", AdbKeepAlive.mayConnect())

        AdbKeepAlive.onFailed()
        assertEquals("第一次失败退避 5s", 5_000L, AdbKeepAlive.backoffMs)
        assertFalse("退避窗口内不许再连", AdbKeepAlive.mayConnect(System.currentTimeMillis()))

        AdbKeepAlive.onFailed()
        assertEquals(10_000L, AdbKeepAlive.backoffMs)
        AdbKeepAlive.onFailed()
        assertEquals(20_000L, AdbKeepAlive.backoffMs)
        AdbKeepAlive.onFailed()
        assertEquals(40_000L, AdbKeepAlive.backoffMs)
        AdbKeepAlive.onFailed()
        assertEquals("退避上限 60s", 60_000L, AdbKeepAlive.backoffMs)
        AdbKeepAlive.onFailed()
        assertEquals("到顶后不再增长", 60_000L, AdbKeepAlive.backoffMs)

        AdbKeepAlive.onConnected()
        assertEquals("成功即归零，否则顺序命令被自己卡死", 0L, AdbKeepAlive.backoffMs)
        assertTrue(AdbKeepAlive.mayConnect())
    }

    @Test
    fun `backoff window expires by time`() {
        AdbKeepAlive.resetForTest()
        AdbKeepAlive.onFailed()
        val now = System.currentTimeMillis()
        assertFalse(AdbKeepAlive.mayConnect(now + 1_000L))
        assertTrue("过了退避时长就该允许重试", AdbKeepAlive.mayConnect(now + 6_000L))
    }

    @Test
    fun `mark stores the result`() {
        AdbKeepAlive.mark(false)
        assertFalse(AdbKeepAlive.healthy)
        AdbKeepAlive.mark(true)
        assertTrue(AdbKeepAlive.healthy)
    }

    @Test
    fun `idle time is tracked from last use`() {
        AdbKeepAlive.resetForTest()
        assertEquals("从没用过时不应被当成空闲", 0L, AdbKeepAlive.idleSince())
        val now = System.currentTimeMillis()
        AdbKeepAlive.touch(now)
        assertTrue(AdbKeepAlive.idleSince(now + 1_000L) >= 1_000L)
    }
}
