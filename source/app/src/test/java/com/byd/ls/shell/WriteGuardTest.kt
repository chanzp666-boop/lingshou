package com.byd.ls.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.131 回归：写车白名单只做「翻译 + 校验」，白名单外、值域外、非整数一律拒绝。
 * 这些用例全是纯函数，不碰设备 —— 目的就是让「拒绝」这件事在打包前就被锁住。
 */
class WriteGuardTest {

    @Test
    fun `unknown action is rejected with the whitelist spelled out`() {
        val p = WriteGuard.plan("ac.explode", 1.0)
        assertFalse(p.ok)
        assertNull(p.raw)
        assertTrue("要给出可用清单：" + p.reason, p.reason.contains("白名单"))
        assertTrue("清单里要能看到 ac.power：" + p.reason, p.reason.contains("ac.power"))
    }

    @Test
    fun `ac temp translates to the calibrated fid address`() {
        val p = WriteGuard.plan("ac.temp", 26.0)
        assertTrue(p.reason, p.ok)
        assertEquals(1000, p.dev)
        assertEquals(1077936168, p.fid)
        assertEquals(26, p.raw)
        assertEquals(26.0, p.human!!, 0.0001)
    }

    @Test
    fun `out of range is rejected instead of clamped`() {
        val p = WriteGuard.plan("ac.temp", 40.0)
        assertFalse(p.ok)
        assertNull(p.raw)
        assertTrue("要说清值域：" + p.reason, p.reason.contains("16.0–32.0"))
    }

    @Test
    fun `fractional value is rejected rather than rounded silently`() {
        val p = WriteGuard.plan("ac.wind", 3.5)
        assertFalse("3.5 级风是静默改值的开始：" + p.reason, p.ok)
        assertTrue(p.reason.contains("整数"))
    }

    @Test
    fun `enum actions only accept their allowed set`() {
        assertTrue(WriteGuard.plan("ac.power", 1.0).ok)
        assertTrue(WriteGuard.plan("ac.power", 0.0).ok)
        val bad = WriteGuard.plan("ac.power", 2.0)
        assertFalse(bad.ok)
        assertTrue("枚举要给出允许值：" + bad.reason, bad.reason.contains("0/1"))
    }

    @Test
    fun `window rear right uses the audited address not the old invalid one`() {
        val p = WriteGuard.plan("window.rearRight", 0.0)
        assertTrue(p.reason, p.ok)
        assertEquals(1001, p.dev)
        assertEquals(1267728408, p.fid)
        assertEquals(0, p.raw)
        // 车机给的是开度百分比（INT_PERCENT），不是 0/1 开关：100 合法，101 拒。
        assertTrue(WriteGuard.plan("window.rearRight", 100.0).ok)
        assertFalse(WriteGuard.plan("window.rearRight", 101.0).ok)
    }

    @Test
    fun `every whitelisted fid exists in the catalog with a matching kind`() {
        for (r in WriteGuard.rules) {
            val f = FidCatalog.byName[r.fidName]
            assertTrue("白名单引用了不存在的 fid：" + r.fidName, f != null)
            assertEquals("kind 不一致：" + r.fidName, r.kind, f!!.kind)
            assertTrue("scale 不能是 0：" + r.fidName, f.scale != 0.0)
        }
    }

    @Test
    fun `describe is one line and never claims ok when rejected`() {
        val ok = WriteGuard.describe(WriteGuard.plan("ac.wind", 3.0))
        assertTrue("计划行要带 raw：" + ok, ok.contains("raw=3"))
        val no = WriteGuard.describe(WriteGuard.plan("ac.wind", 9.0))
        assertTrue("拒绝行要带动作与原因：" + no, no.startsWith("拒绝 ac.wind："))
    }
}
